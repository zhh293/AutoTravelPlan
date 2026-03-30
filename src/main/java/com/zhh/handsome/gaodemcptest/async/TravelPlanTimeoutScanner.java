package com.zhh.handsome.gaodemcptest.async;

import com.zhh.handsome.gaodemcptest.websocket.TravelPlanWebSocketSender;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class TravelPlanTimeoutScanner {

    private final TaskTimeoutQueue taskTimeoutQueue;
    private final TaskStateStore taskStateStore;
    private final TravelPlanWebSocketSender webSocketSender;
    private final RedisTemplate<String, Object> redisTemplate;
    @Value("${timeout.queue.scan-batch-size:100}")
    private int batchSize;
    @Value("${timeout.queue.lock-ttl-millis:5000}")
    private long lockTtlMillis;
    private static final String LOCK_KEY = "travel-plan:timeout:scan:lock";
    private final DefaultRedisScript<Long> releaseLockScript = buildReleaseLockScript();

    @Scheduled(fixedDelayString = "${timeout.queue.scan-interval-millis:5000}")
    public void scan() {
        String lockValue = UUID.randomUUID().toString();
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(LOCK_KEY, lockValue,
                java.time.Duration.ofMillis(lockTtlMillis));
        if (locked == null || !locked) {
            return;
        }
        long now = System.currentTimeMillis();
        try {
            taskTimeoutQueue.reclaim(now, batchSize);
            taskTimeoutQueue.moveDue(now, batchSize);
            List<String> ready = taskTimeoutQueue.reserveReady(now, batchSize);
            if (ready.isEmpty()) {
                return;
            }
            for (String taskId : ready) {
                TaskState state = taskStateStore.get(taskId);
                if (state == TaskState.DONE || state == TaskState.ERROR || state == TaskState.EXPIRED) {
                    taskTimeoutQueue.ack(taskId);
                    continue;
                }
                taskStateStore.transition(taskId, TaskAction.TIMEOUT);
                webSocketSender.sendError(taskId, "任务超时");
                webSocketSender.closeTask(taskId);
                taskStateStore.delete(taskId);
                taskTimeoutQueue.ack(taskId);
            }
        } finally {
            redisTemplate.execute(releaseLockScript, List.of(LOCK_KEY), lockValue);
        }
    }

    private DefaultRedisScript<Long> buildReleaseLockScript() {
        String script = """
                if redis.call('GET', KEYS[1]) == ARGV[1] then
                    return redis.call('DEL', KEYS[1])
                end
                return 0
                """;
        DefaultRedisScript<Long> scriptObj = new DefaultRedisScript<>();
        scriptObj.setScriptText(script);
        scriptObj.setResultType(Long.class);
        return scriptObj;
    }
}
