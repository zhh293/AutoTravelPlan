# Redis 延迟队列实现思路（双 ZSet + List）

本设计用于任务超时处理，目标是可恢复、可扩展、可解释，并接近 Redisson 的核心逻辑。

---

## 1. 核心数据结构

- DELAY_ZSET（延迟集合）
  - Key：travel-plan:timeout:zset
  - Score：任务到期时间（毫秒）
  - Member：taskId

- PROCESSING_ZSET（处理中集合 / 可见性超时）
  - Key：travel-plan:timeout:processing:zset
  - Score：可见性截止时间（now + visibilityMillis）
  - Member：taskId

- READY_LIST（就绪队列）
  - Key：travel-plan:timeout:ready:list
  - 先进先出，存放已到期可处理的任务

---

## 2. 任务生命周期

1. 提交任务  
   写入 DELAY_ZSET，score = 到期时间  
2. 到期任务搬运  
   扫描 DELAY_ZSET 中到期任务，原子搬运到 READY_LIST，并放入 PROCESSING_ZSET  
3. 消费处理  
   从 READY_LIST 取任务执行  
4. 确认完成  
   从 PROCESSING_ZSET 删除任务  
5. 回收未完成任务  
   处理节点崩溃时，任务未 ack，超时后回收到 READY_LIST

---

## 3. 原子操作保证

搬运与回收都使用 Lua 脚本执行：
- DELAY_ZSET → PROCESSING_ZSET + READY_LIST
- PROCESSING_ZSET → READY_LIST

这样可以避免并发场景下重复消费或丢任务。

---

## 4. 可见性超时（Visibility Timeout）

处理流程中任务会进入 PROCESSING_ZSET，如果超时未 ack：

```
taskId 在 PROCESSING_ZSET 中的 score = now + visibilityMillis
```

扫描器会在到期后回收任务，保证不会永久丢失。

---

## 5. 扫描器职责（企业级关键点）

定时扫描器负责：

1. 回收超时未完成任务  
2. 搬运到期任务  
3. 消费 READY_LIST  
4. 执行超时处理  
5. 清理状态 + 关闭 WebSocket 通道  

扫描器使用分布式锁保证多实例不会重复消费。

---

## 6. 工作流程详解（面试可直接讲）

下面用“代码视角”把每一步对应到具体类和方法，保证能对着代码讲清楚。

### 6.1 任务写入（Producer）
1. 生成 taskId  
2. 计算超时时间 deadline = now + timeout  
3. 写入 DELAY_ZSET  
   ZADD delay_zset deadline taskId

为什么这样做  
ZSet 天然按 score 排序，可以精确定位到期任务，写入复杂度 O(logN)，适合高并发。

**代码对应**  
- `TravelPlanTaskProducer.submitTask`  
  - 初始化状态：`taskStateStore.init`  
  - 入队状态：`taskStateStore.transition(ENQUEUE)`  
  - 加入延迟队列：`taskTimeoutQueue.add(taskId, now + timeout)`  
  - 写入主任务队列：`redisTemplate.opsForList().leftPush(QUEUE_KEY, msg)`

---

### 6.2 到期搬运（Scanner + Lua 原子脚本）
1. 扫描到期任务  
   ZRANGEBYSCORE delay_zset 0 now LIMIT 0 N  
2. 原子搬运（Lua）  
   - 从 DELAY_ZSET 移除  
   - 加入 PROCESSING_ZSET（score = now + visibility）  
   - 推入 READY_LIST

为什么必须用 Lua  
不原子会出现并发下的重复消费或丢任务。

**代码对应**  
- `TaskTimeoutQueue.moveDue`  
  - Lua 脚本把到期任务从 DELAY_ZSET 原子搬运到 READY_LIST  
  - 同时写入 PROCESSING_ZSET（score = now + visibility）
- `TaskTimeoutQueue.buildMoveDueScript`  
  - `ZREM delay_zset`  
  - `ZADD processing_zset`  
  - `RPUSH ready_list`

---

### 6.3 消费处理（Ready List）
1. 从 READY_LIST 领取任务并重新写入 PROCESSING_ZSET  
   LPOP ready_list + ZADD processing_zset (visibility)  
2. 执行业务逻辑  
   - 触发超时处理  
   - 推送 WebSocket 失败提示  
   - 关闭对应连接  
   - 删除任务状态

这里的核心目标  
把超时处理与正常任务处理解耦，避免主流程被拖慢。

**代码对应**  
- `TravelPlanTimeoutScanner.scan`  
  - `taskTimeoutQueue.reserveReady(now, batchSize)` 领取就绪任务并写回 processing_zset  
  - 对每个 taskId 执行：  
    - `taskStateStore.transition(TIMEOUT)`  
    - `webSocketSender.sendError`  
    - `webSocketSender.closeTask`  
    - `taskStateStore.delete`  
    - `taskTimeoutQueue.ack`

---

### 6.4 ACK 确认完成
任务成功处理后：  
从 PROCESSING_ZSET 删除 taskId，这一步表示消费完成。

**代码对应**  
- `TaskTimeoutQueue.ack`  
  - `ZREM processing_zset taskId`

---

### 6.5 可见性超时回收（Reclaim）
如果消费者异常崩溃，任务会一直留在 PROCESSING_ZSET。  

扫描器会执行：  
```
ZRANGEBYSCORE processing_zset 0 now LIMIT 0 N
```

回收逻辑：  
- 从 PROCESSING_ZSET 删除  
- 再次放回 READY_LIST  

这样保证任务不会永远丢失。

**代码对应**  
- `TaskTimeoutQueue.reclaim`  
  - `ZRANGEBYSCORE processing_zset 0 now LIMIT 0 N`  
  - `ZREM processing_zset`  
  - `RPUSH ready_list`

---

### 6.6 分布式锁保证单实例扫描
多实例部署时，扫描线程必须互斥：  

- SETNX lock_key value PX ttl  
- 扫描完成后用 Lua 删除锁  

保证同一批任务不会被多台机器重复处理。

**代码对应**  
- `TravelPlanTimeoutScanner.scan`  
  - `setIfAbsent(LOCK_KEY, value, ttl)`  
  - Lua 删除锁 `buildReleaseLockScript`

---

## 7. 面试官提问时的回答示例

问：为什么不用 List 轮询？  
答：List 轮询无法精确控制延迟，扫描成本高，且缺乏可见性回收机制。ZSet 能精确排序到期时间，配合 processing_zset 实现可靠性。

问：为什么需要 processing_zset？  
答：它是可见性超时保障，防止消费者异常导致任务永久丢失。

问：为什么要 Lua？  
答：为了把取到期任务、写就绪队列、写处理中集合做成原子操作，防止并发下的重复消费。

---

## 8. 配置项（可调参数）

```yaml
timeout:
  queue:
    task-timeout-millis: 600000
    visibility-millis: 30000
    scan-interval-millis: 5000
    scan-batch-size: 100
    lock-ttl-millis: 5000
```

---

## 9. 面试官可听懂的总结

我实现了一个企业级的 Redis 延迟队列，核心是双 ZSet + Ready List + 可见性超时。  
通过 Lua 原子搬运避免并发错乱，并加了扫描锁保证多实例一致性，  
既能精确触发又能恢复异常任务，整体思路与 Redisson 延迟队列一致。

---

## 10. 关键代码位置（便于现场翻讲）

- 延迟队列核心实现  
  TaskTimeoutQueue.java  
- 超时扫描器（含锁 + 搬运 + 回收）  
  TravelPlanTimeoutScanner.java  
- 任务提交时写入延迟队列  
  TravelPlanTaskProducer.java  
- 任务完成/失败时取消延迟队列  
  TravelPlanTaskConsumer.java

---

## 11. 正常任务与超时任务的关系（避免面试官误解）

- 正常任务处理完成：`TravelPlanTaskConsumer` 会调用 `taskTimeoutQueue.cancel(taskId)`，把任务从三种结构全部移除  
- 超时任务处理：由 `TravelPlanTimeoutScanner` 统一触发，不进入正常业务逻辑  

这样保证超时处理不会影响正常流程，并且不会重复处理。
