# Redis 延迟队列实现思路（双 ZSet + List）

本设计用于“任务超时处理”，目标是**可恢复、可扩展、可解释**，并接近 Redisson 的核心逻辑。

---

## 1. 核心数据结构

- **DELAY_ZSET**（延迟集合）
  - Key：`travel-plan:timeout:zset`
  - Score：任务到期时间（毫秒）
  - Member：`taskId`

- **PROCESSING_ZSET**（处理中集合 / 可见性超时）
  - Key：`travel-plan:timeout:processing:zset`
  - Score：可见性截止时间（now + visibilityMillis）
  - Member：`taskId`

- **READY_LIST**（就绪队列）
  - Key：`travel-plan:timeout:ready:list`
  - 先进先出，存放“已到期可处理”的任务

---

## 2. 任务生命周期

1. **提交任务**
   - 写入 `DELAY_ZSET`，score = 到期时间
2. **到期任务搬运**
   - 扫描 `DELAY_ZSET` 中到期任务
   - 原子搬运到 `READY_LIST`
   - 同时放入 `PROCESSING_ZSET`（用于可见性超时）
3. **消费处理**
   - 从 `READY_LIST` 取任务执行
4. **确认完成**
   - 从 `PROCESSING_ZSET` 删除任务
5. **回收未完成任务**
   - 若处理节点崩溃，任务未 ack
   - 可见性超时到期后从 `PROCESSING_ZSET` 回收回 `READY_LIST`

---

## 3. 原子操作保证

**搬运与回收都使用 Lua 脚本执行：**
- `DELAY_ZSET → PROCESSING_ZSET + READY_LIST`
- `PROCESSING_ZSET → READY_LIST`

这样可以避免并发场景下重复消费或丢任务。

---

## 4. 可见性超时（Visibility Timeout）

处理流程中任务会进入 `PROCESSING_ZSET`，如果超时未 ack：

```
taskId 在 PROCESSING_ZSET 中的 score = now + visibilityMillis
```

扫描器会在到期后回收任务，保证不会永久丢失。

---

## 5. 扫描器职责（企业级关键点）

定时扫描器负责：

1. **回收超时未完成任务**
2. **搬运到期任务**
3. **消费 READY_LIST**
4. **执行超时处理**
5. **清理状态 + 关闭 WebSocket 通道**

扫描器使用 **分布式锁** 保证多实例不会重复消费。

---

## 6. 配置项（可调参数）

```yaml
timeout:
  queue:
    task-timeout-millis: 600000      # 任务超时阈值
    visibility-millis: 30000         # 可见性超时
    scan-interval-millis: 5000       # 扫描频率
    scan-batch-size: 100             # 批量大小
    lock-ttl-millis: 5000            # 扫描锁TTL
```

---

## 7. 面试官可听懂的总结

> 我实现了一个企业级的 Redis 延迟队列，核心是“**双 ZSet + Ready List + 可见性超时**”，  
> 通过 Lua 原子搬运避免并发错乱，并加了扫描锁保证多实例一致性，  
> 既能精确触发又能恢复异常任务，整体思路与 Redisson 延迟队列一致。

---

## 8. 关键代码位置（便于现场翻讲）

- 延迟队列核心实现  
  `TaskTimeoutQueue.java`
- 超时扫描器（含锁 + 搬运 + 回收）  
  `TravelPlanTimeoutScanner.java`
- 任务提交时写入延迟队列  
  `TravelPlanTaskProducer.java`
- 任务完成/失败时取消延迟队列  
  `TravelPlanTaskConsumer.java`
