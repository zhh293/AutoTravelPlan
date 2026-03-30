# 🚀 Java 全栈开发项目亮点 (BasicBack & GaoDeMCPTest)

> 该文档整理了项目中**最具含金量**的技术亮点，专为技术面试设计。每个亮点都按照 **"背景(Situation) -> 任务(Task) -> 行动(Action) -> 结果(Result)"** 的逻辑进行了梳理，方便在面试中展开讲述。

---

## 🌟 亮点 1：多级缓存架构 (Caffeine + Redis) 防止缓存雪崩与击穿
**核心代码**: `com.tmd.config.L2CacheConfig` (BasicBack)

*   **面试切入点**: "你在项目中是如何处理热点数据的高并发访问的？"
*   **技术深度**:
    *   **问题**: 单纯使用 Redis 存在网络 IO 开销，且在极端流量下（如热点帖子）可能导致 Redis 带宽打满或单点故障。
    *   **解决方案**: 设计了 **L1 (进程内 Caffeine) + L2 (分布式 Redis)** 的多级缓存架构。
    *   **实现细节**:
        *   **L1 缓存**: 使用 Caffeine，配置 `expireAfterWrite` 和 `maximumSize`，用于存储极高频访问的只读数据（如系统配置、热门文章），响应时间微秒级。
        *   **L2 缓存**: 使用 Redis，保证多实例间的数据一致性。
        *   **空值缓存**: 配置了 `allowNullValues = true`，对于数据库中不存在的 Key 也进行短时间缓存，有效防止了**缓存穿透**（黑客恶意攻击不存在的 ID）。
    *   **优势**: 能够承受 Redis 宕机后的短时流量冲击，大幅降低网络开销。

## 🤖 亮点 2：基于 SSE 与 WebSocket 的流式 AI 响应系统
**核心代码**: `com.zhh.handsome.gaodemcptest.service.TripPlanServiceImpl` (GaoDeMCPTest)

*   **面试切入点**: "大模型的响应速度很慢，你是如何优化用户体验的？"
*   **技术深度**:
    *   **问题**: 复杂的 AI 旅行规划任务耗时可能长达 30 秒，传统的 HTTP 请求会导致前端长时间白屏甚至超时。
    *   **解决方案**: 实现了 **SSE (Server-Sent Events)** 和 **WebSocket** 双模式流式传输。
    *   **创新点 (Code Highlight)**:
        *   **增量 JSON 修复**: LLM 流式返回的数据往往是碎片化的（如 `{"day": 1, "ro`...`ute": [...]`），直接解析会报错。我编写了 `extractCompleteJson` 算法，在内存中维护一个缓冲区，利用**栈结构**（括号匹配计数）实时检测并提取完整的 JSON 对象，实现“边生成、边渲染”。
        *   **并发控制**: 使用 `ConcurrentHashMap` 管理 WebSocket 会话，确保多用户并发规划时不串号。

## 🧠 亮点 3：多 Agent "议会制" 决策系统 (Multi-Agent Council)
**核心代码**: `com.zhh.handsome.gaodemcptest.model.LocationLlmCouncil` (GaoDeMCPTest)

*   **面试切入点**: "你的 AI 功能只是简单的调用 API 吗？有没有做过复杂的 Prompt 工程或 Agent？"
*   **技术深度**:
    *   **问题**: 单次 LLM 调用在处理模糊地理位置（如“那家很好吃的面馆”）时，幻觉率高，准确性差。
    *   **解决方案**: 模仿人类决策，实现了一个 **3 阶段议会系统**：
        1.  **Stage 1 (Collect)**: 并行调用多个不同 Prompt 侧重的模型/角色，分别给出判断。
        2.  **Stage 2 (Rank)**: 让模型对第一阶段的所有答案进行打分和逻辑验证。
        3.  **Stage 3 (Synthesize)**: 综合高分答案生成最终结论。
    *   **价值**: 这种 **Chain-of-Thought (CoT)** 的工程化落地，显著提高了复杂意图识别的准确率，比单一 RAG 更智能。

## 🌐 亮点 4：Netty WebSocket 独立通道与心跳治理
**核心代码**: `com.zhh.handsome.gaodemcptest.websocket.netty` (GaoDeMCPTest)

*   **面试切入点**: "为什么不用原生 WebSocket，而要引入 Netty？"
*   **技术深度**:
    *   **问题**: Spring WebSocket 依附于主容器，流量抖动和线程池压力会影响推送稳定性。
    *   **解决方案**: 用 Netty 独立启动 WebSocket 服务，隔离主应用线程池。
    *   **实现细节**:
        *   **心跳机制**: `IdleStateHandler` 定期 Ping，及时发现断连。
        *   **握手校验**: 升级阶段校验 `taskId`，无效连接直接拒绝。
        *   **精准推送**: 发送器同时支持 Spring Session 和 Netty Channel。
    *   **优势**: 推送链路更短、可控性更强、稳定性更高。

## ⚡ 亮点 5：基于 Redis List 的异步削峰填谷
**核心代码**: `com.zhh.handsome.gaodemcptest.async.TravelPlanTaskConsumer` (GaoDeMCPTest)

*   **面试切入点**: "如果有大量用户同时请求生成旅行计划，系统怎么抗？"
*   **技术深度**:
    *   **架构设计**: 采用 **生产者-消费者** 模型。
        *   **Producer**: Controller 接收请求后，仅将 TaskID 和参数序列化推入 Redis List (`travel-plan:queue`)，立即返回“处理中”状态，耗时 < 10ms。
        *   **Consumer**: 独立的后台线程池（`Thread` + `while(true)`），利用 Redis 的 `BLPOP` (阻塞读) 获取任务。
    *   **实现细节**:
        *   线程池大小根据 CPU 核心数动态调整 (`availableProcessors() * 2`)。
        *   包含完善的**反序列化容错**机制，防止脏数据导致消费者线程崩溃。
    *   **优势**: 典型的**削峰填谷**策略，保护后端昂贵的 LLM 资源不被突发流量压垮。

## 🧭 亮点 6：任务状态机 + 原子幂等流转
**核心代码**: `com.zhh.handsome.gaodemcptest.async.TaskStateStore` (GaoDeMCPTest)

*   **面试切入点**: "你是怎么保证异步任务在并发下不会乱序流转的？"
*   **技术深度**:
    *   **问题**: 异步任务存在并发更新、重复消息、状态回滚等风险。
    *   **解决方案**: 用 Redis + Lua 实现 CAS 风格原子状态流转。
    *   **实现细节**:
        *   **状态机定义**: 明确 `CREATED → QUEUED → RUNNING → PUSHING → DONE/ERROR/EXPIRED` 的合法流转。
        *   **原子校验**: 只有当当前状态与期望状态一致时才允许写入下一状态，否则拒绝更新。
    *   **优势**: 幂等、并发安全、可追踪。

## ⏱️ 亮点 7：企业级 Redis 延迟队列（双 ZSet + List）
**核心代码**: `com.zhh.handsome.gaodemcptest.async.TaskTimeoutQueue` (GaoDeMCPTest)

*   **面试切入点**: "延迟任务如何做到可靠投递与可恢复？"
*   **技术深度**:
    *   **结构**: `DELAY_ZSET` + `PROCESSING_ZSET` + `READY_LIST`。
    *   **原子搬运**: Lua 脚本把到期任务从延迟集合原子转移到就绪队列，并写入可见性超时集合。
    *   **回收机制**: 处理节点异常时，超时任务从 `PROCESSING_ZSET` 回收到 `READY_LIST`。
    *   **分布式扫描锁**: 避免多实例重复扫描。
    *   **可配置**: 超时阈值、可见性超时、扫描频率均支持配置化。

## 📱 亮点 8：高安全性的扫码登录全流程 (状态机设计)
**核心代码**: `com.tmd.controller.QRCodeController` (BasicBack)

*   **面试切入点**: "扫码登录的交互流程是怎样的？如何保证安全？"
*   **技术深度**:
    *   **状态流转**: 设计了清晰的二维码状态机：`CREATED` -> `SCANNED` (已扫码待确认) -> `CONFIRMED` (登录成功) / `EXPIRED` (过期)。
    *   **实现逻辑**:
        1.  **PC 端**: 轮询 (或长连接) 检查 Redis 中该 `qrCodeId` 的状态。
        2.  **手机端**: 携带 Token 扫描 -> 调用 `/scan` 接口修改状态为 `SCANNED` -> 点击确认调用 `/confirm` 交换 Token。
    *   **安全考量**: 二维码 ID 绑定过期时间，且确认接口必须校验手机端用户的 `Session/JWT`，防止伪造请求。

## 💰 亮点 9：支付中台的最终一致性与幂等处理
**核心代码**: `com.tmd.service.impl.WechatPayServiceImpl` (BasicBack)

*   **面试切入点**: "涉及金钱的业务，你是如何保证数据不出错的？"
*   **技术深度**:
    *   **手动签名与验签**: 没有单纯依赖 SDK，而是深入理解微信支付 v3 协议，实现了基于 `PrivateKey` 的 SHA256-RSA 签名和证书自动更新。
    *   **Mock 机制**: 通过 `wechatPayProperties.isMock()` 开关，在开发环境模拟完整的支付/退款闭环，方便前端联调，无需真实资金。
    *   **最终一致性**:
        *   支付回调 (`handlePayNotify`) 采用**幂等设计**：收到回调先查单，如果状态已是 SUCCESS 则直接返回，防止微信重复通知导致的数据错误。
        *   退款逻辑包含完整的异常处理和状态同步。

## 🗺️ 亮点 10：RAG (检索增强生成) 之地理数据锚定
**核心代码**: `com.zhh.handsome.gaodemcptest.model.AmapPoiUtils`

*   **面试切入点**: "如何解决 AI 在特定领域（如旅游）胡说八道的问题？"
*   **技术深度**:
    *   **原理**: 并不是盲目微调模型，而是采用 **RAG** 思想。
    *   **实现**: 在调用 LLM 规划行程前，先根据经纬度调用 **高德地图周边搜索 API** 获取真实的 POI 数据（名称、评分、地址）。
    *   **Context 构建**: 将这些结构化数据清洗后通过 `buildPoiListText` 注入到 Prompt 中，强制 AI 只能从提供的真实列表中选择景点。
    *   **结果**: 彻底解决了 AI 编造不存在景点的 "幻觉" 问题。

## 🛡️ 亮点 11：自定义注解式限流 (AOP + Redis)
**核心代码**: `com.tmd.aspect.RateLimitAspect` (BasicBack)

*   **面试切入点**: "如何防止某个 IP 恶意刷接口？"
*   **技术深度**:
    *   **实现**: 编写自定义注解 `@RateLimit`，利用 Spring AOP 切面拦截请求。
    *   **算法**: 基于 Redis 的计数器模式（或令牌桶算法）。Key 设计为 `rate_limit:ip:methodName`。
    *   **Lua 脚本**: 为了保证 "读取-判断-自增" 的原子性，使用了 Redis Lua 脚本执行限流逻辑，避免并发场景下的计数失效。

---
> **面试官贴士**: 在介绍这些亮点时，建议先说遇到的**困难**，再说**方案**，最后强调**收益**。上面的代码路径可以帮助你快速回顾具体实现细节。
