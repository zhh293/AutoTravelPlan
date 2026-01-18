# TripPlanService 技术实现与面试指南

## 1. 功能概述
`TripPlanService` 是智能旅行规划的核心服务类。它接收用户的目的地、天数和偏好，通过编排**高德地图 API**（POI搜索、路径规划、天气）与 **Spring AI**（大模型生成），实时生成并推送个性化的每日行程路线。

**核心亮点**：
- **流式响应 (SSE)**：采用 Server-Sent Events 技术，实现打字机效果的实时推送，避免用户长时间等待。
- **混合智能**：结合规则引擎（高德数据）与生成式 AI（行程编排），既保证数据准确性（经纬度），又具备人性化规划能力。
- **鲁棒的 JSON 流处理**：实现了基于缓冲区的增量 JSON 解析器，完美处理 AI 流式输出的碎片化数据。

---

## 2. 技术架构栈
- **Spring AI**：调用大语言模型（LLM）进行行程编排。
- **Spring Web SSE (`SseEmitter`)**：实现 HTTP 长连接流式推送。
- **Apache HttpClient**：高性能调用高德 REST API。
- **Jackson**：处理 JSON 序列化与反序列化。
- **Spring TaskExecutor**：线程池管理，异步执行耗时任务，避免阻塞主线程。
- **Concurrent Collections**：使用 `ConcurrentHashMap` 等保证多线程安全。

---

## 3. 核心业务流程
1. **参数校验**：检查城市和天数有效性。
2. **异步并行数据获取**：
   - **天气查询**：调用高德天气 API，获取未来几天的天气。
   - **POI 候选集获取**：调用高德 POI 搜索 API，获取该城市的热门景点列表（带经纬度）。
3. **构建 AI Prompt**：将候选景点、天数、偏好封装进 Prompt，并强制约束 AI 输出特定格式的 JSON 流。
4. **流式处理与解析（核心难点）**：
   - 监听 AI 的流式响应 (`Flux`)。
   - 将碎片化的字符流累积到缓冲区 (`StringBuilder`)。
   - **实时检测并提取完整的 JSON 对象**。
5. **行程增强与推送**：
   - 解析出景点后，校验/补全经纬度（若 AI 幻觉生成了未知景点，回查高德 API）。
   - 按天聚合景点，当一天的景点生成完毕，调用高德**路径规划 API** 生成最优驾车路线。
   - 通过 SSE 实时推送 `trip-weather`, `trip-data`, `trip-error` 等事件给前端。

---

## 4. 关键代码深度解析（面试重点）

### 4.1 缓冲区处理 JSON 字符串（核心考点）
**面试问题**：*AI 返回的数据是流式的（一个个字符或片段），你怎么知道什么时候接收到了一个完整的 JSON 对象并进行解析？*

**实现逻辑 (`extractCompleteJson` 方法)**：
这是一个经典的**增量解析**问题。直接解析未完成的 JSON 字符串会抛出异常，因此需要维护一个缓冲区。

1. **缓冲区 (`StringBuilder buf`)**：用来暂存 AI 返回的所有碎片文本。
2. **括号计数法**：
   - 维护 `lastLeftBracketCount` (左括号数量) 和 `lastRightBracketCount` (右括号数量)。
   - 遍历缓冲区中的字符，遇到 `{` 计数+1，遇到 `}` 计数+1。
3. **完整性判断**：
   - 当 `leftCount == rightCount` 且 `leftCount > 0` 时，意味着当前缓冲区内从开头到当前位置，刚好构成了一个（或多个嵌套的）完整 JSON 对象。
4. **提取与重置**：
   - 截取该段字符串，尝试解析。
   - 从缓冲区中删除已处理的部分。
   - 重置计数器，继续处理剩余字符。

**代码片段精讲**：
```java
// 核心逻辑：不依赖正则，不依赖尝试解析，而是基于结构的括号匹配
while (i < buf.length()) {
    char c = buf.charAt(i);
    if (c == '{') lastLeftBracketCount++;
    else if (c == '}') lastRightBracketCount++;
    
    // 平衡点检测：左右括号数量相等，说明一个顶级JSON对象闭合了
    if (lastLeftBracketCount == lastRightBracketCount && lastLeftBracketCount > 0) {
        String completeJson = buf.substring(0, i + 1); // 截取完整对象
        completeJsonList.add(completeJson);
        buf.delete(0, i + 1); // 移除已处理部分，缓冲区只留未完成的碎片
        // ...重置计数器...
    }
    i++;
}
```

**为什么这样做？**
- **性能高**：不需要在每收到一个字符时都调用昂贵的 `JSON.parse`。
- **容错性强**：天然支持嵌套 JSON 对象（如 `{ "spot": { "name": "x" } }`）。
- **线程安全**：使用了 `synchronized (buf)` 块，确保在多线程环境下（虽然这里主要是单消费者）缓冲区状态的一致性。

### 4.2 SSE 异步推送设计
**面试问题**：*为什么选择 SSE 而不是 WebSocket？如何处理连接超时？*

**回答思路**：
- **选择 SSE**：这是一个单向数据流场景（服务器 -> 客户端），不需要 WebSocket 的双向全双工通信，SSE 更轻量，且基于 HTTP 协议，对防火墙友好。
- **异步执行**：
  ```java
  // 主线程立即返回 emitter，不阻塞 Servlet 容器线程
  SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
  taskExecutor.execute(() -> { 
      // 耗时业务逻辑在这里跑
  });
  return emitter;
  ```
- **超时与回调**：显式注册了 `onTimeout`, `onCompletion`, `onError` 回调，防止连接僵死导致资源泄漏。

### 4.3 预防 AI 幻觉与数据一致性
**面试问题**：*如果 AI 编造了一个不存在的景点，或者返回的经纬度是错的怎么办？*

**解决方案**：
1. **Prompt 约束**：在 Prompt 中明确要求 AI *"仅从以下景点中选择"*，并传入了高德 API 查到的真实 POI 列表。
2. **二次校验 (`verifyOrCompleteSpot`)**：
   - 即使 AI 返回了数据，代码中也会检查 `lng/lat` 是否有效。
   - 如果经纬度缺失或无效，会触发**兜底机制**：再次调用高德 `/v3/place/text` 接口，用景点名称反查准确的经纬度，确保前端地图打点准确。

---

## 5. 面试模拟问答 (Q&A)

**Q1: 这里为什么用 `StringBuilder` 而不是 `StringBuffer`？**
**A**: 在 `streamPlan` 方法内部，虽然是在 `taskExecutor` 的线程中运行，但 `chatModel.stream().subscribe()` 的回调可能会在 Reactor 的线程中触发。为了保险起见，代码中使用了 `synchronized (buf)` 进行同步块保护。因此，使用非线程安全的 `StringBuilder` 配合显式的 `synchronized` 块，比直接用 `StringBuffer` 提供了更细粒度的控制，且性能略好（无锁竞争时）。不过在这个特定场景下，核心是**状态（括号计数）与缓冲区的一致性**，必须加锁，所以 `StringBuilder` 是合适的选择。

**Q2: 你的括号计数法能处理 JSON 字符串中包含大括号的情况吗？比如 `{"desc": "a{b}c"}`？**
**A**: 这是一个很好的边界问题。当前的简单实现**不能**完美处理这种情况，它会将字符串内的 `}` 误判为对象结束。
*改进思路*：引入一个 `inString` 布尔标记。当遇到 `"` 时翻转 `inString` 状态。只有当 `inString == false` 时，才统计 `{` 和 `}`。这在面试中提出是一个加分项，表明考虑到了词法分析的细节。但在这个项目中，AI 被 Prompt 强约束为只输出结构化字段，出现这种情况概率极低，当前实现是基于“AI 遵守协议”的乐观假设。

**Q3: 为什么要把高德 API 的调用放在 Service 层，而不是前端直接调？**
**A**: 
1. **安全性**：高德 API Key 存储在后端，避免暴露给前端。
2. **数据清洗**：后端可以对 API 返回的原始数据进行过滤（如剔除无经纬度的脏数据）、转换和缓存。
3. **编排能力**：后端可以将天气、景点、AI 建议聚合成一个统一的业务流，前端只需要监听一个 SSE 接口即可，降低了前端复杂度。

**Q4: 线程池是如何配置的？为什么要用线程池？**
**A**: 原始代码可能直接 `new Thread()`，这会导致高并发下线程数爆炸，频繁创建销毁线程消耗 CPU。改进后注入了 `TaskExecutor`（通常配置为 `ThreadPoolTaskExecutor`），实现了线程复用、核心线程数控制和拒绝策略，保证了系统的稳定性。
