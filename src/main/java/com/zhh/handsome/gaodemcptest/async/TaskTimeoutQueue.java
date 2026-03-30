package com.zhh.handsome.gaodemcptest.async;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
@RequiredArgsConstructor
public class TaskTimeoutQueue {

    private static final String DELAY_ZSET = "travel-plan:timeout:zset";
    private static final String PROCESSING_ZSET = "travel-plan:timeout:processing:zset";
    private static final String READY_LIST = "travel-plan:timeout:ready:list";
    private static final String RETRY_HASH = "travel-plan:timeout:retry:hash";
    private static final String DLQ_LIST = "travel-plan:timeout:dlq:list";
    private final RedisTemplate<String, Object> redisTemplate;
    private final DefaultRedisScript<List> moveDueScript = buildMoveDueScript();
    private final DefaultRedisScript<List> reclaimScript = buildReclaimScript();
    private final DefaultRedisScript<List> reserveFromReadyScript = buildReserveFromReadyScript();
    private final long visibilityMillis;
    private final int maxRetryTimes;

    public TaskTimeoutQueue(
            RedisTemplate<String, Object> redisTemplate,
            @Value("${timeout.queue.visibility-millis:30000}") long visibilityMillis,
            @Value("${timeout.queue.max-retry-times:3}") int maxRetryTimes) {
        this.redisTemplate = redisTemplate;
        this.visibilityMillis = visibilityMillis;
        this.maxRetryTimes = maxRetryTimes;
    }

    public void add(String taskId, long deadlineMillis) {
        if (taskId == null) {
            return;
        }
        redisTemplate.opsForZSet().add(DELAY_ZSET, taskId, deadlineMillis);
    }

    public void cancel(String taskId) {
        if (taskId == null) {
            return;
        }
        redisTemplate.opsForZSet().remove(DELAY_ZSET, taskId);
        redisTemplate.opsForZSet().remove(PROCESSING_ZSET, taskId);
        redisTemplate.opsForList().remove(READY_LIST, 0, taskId);
        redisTemplate.opsForHash().delete(RETRY_HASH, taskId);
    }

    public List<String> moveDue(long nowMillis, int limit) {
        Object result = redisTemplate.execute(
                moveDueScript,
                List.of(DELAY_ZSET, READY_LIST, RETRY_HASH),
                String.valueOf(nowMillis),
                String.valueOf(limit));
        return toStringList(result);
    }

    public List<String> reclaim(long nowMillis, int limit) {
        Object result = redisTemplate.execute(
                reclaimScript,
                List.of(PROCESSING_ZSET, READY_LIST, RETRY_HASH, DLQ_LIST),
                String.valueOf(nowMillis),
                String.valueOf(limit),
                String.valueOf(maxRetryTimes));
        return toStringList(result);
    }

    public List<String> pollReady(int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }
        List<Object> result = redisTemplate.opsForList().leftPop(READY_LIST, limit);
        if (result == null || result.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> list = new ArrayList<>();
        for (Object obj : result) {
            list.add(String.valueOf(obj));
        }
        return list;
    }

    public List<String> reserveReady(long nowMillis, int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }
        Object result = redisTemplate.execute(
                reserveFromReadyScript,
                List.of(READY_LIST, PROCESSING_ZSET),
                String.valueOf(limit),
                String.valueOf(nowMillis + visibilityMillis));
        return toStringList(result);
    }

    public void ack(String taskId) {
        if (taskId == null) {
            return;
        }
        redisTemplate.opsForZSet().remove(PROCESSING_ZSET, taskId);
        redisTemplate.opsForHash().delete(RETRY_HASH, taskId);
    }

    private DefaultRedisScript<List> buildMoveDueScript() {
        String script = """
                local delayKey = KEYS[1]
                local readyKey = KEYS[2]
                local retryKey = KEYS[3]
                local now = tonumber(ARGV[1])
                local limit = tonumber(ARGV[2])
                local due = redis.call('ZRANGEBYSCORE', delayKey, 0, now, 'LIMIT', 0, limit)
                if #due == 0 then
                    return {}
                end
                for i = 1, #due do
                    local member = due[i]
                    redis.call('ZREM', delayKey, member)
                    redis.call('HSETNX', retryKey, member, 0)
                    redis.call('RPUSH', readyKey, member)
                end
                return due
                """;
        DefaultRedisScript<List> scriptObj = new DefaultRedisScript<>();
        scriptObj.setScriptText(script);
        scriptObj.setResultType(List.class);
        return scriptObj;
    }

    private DefaultRedisScript<List> buildReclaimScript() {
        String script = """
                local processingKey = KEYS[1]
                local readyKey = KEYS[2]
                local retryKey = KEYS[3]
                local dlqKey = KEYS[4]
                local now = tonumber(ARGV[1])
                local limit = tonumber(ARGV[2])
                local maxRetry = tonumber(ARGV[3])
                local stuck = redis.call('ZRANGEBYSCORE', processingKey, 0, now, 'LIMIT', 0, limit)
                if #stuck == 0 then
                    return {}
                end
                for i = 1, #stuck do
                    local member = stuck[i]
                    redis.call('ZREM', processingKey, member)
                    local retries = redis.call('HINCRBY', retryKey, member, 1)
                    if retries > maxRetry then
                        redis.call('RPUSH', dlqKey, member)
                        redis.call('HDEL', retryKey, member)
                    else
                        redis.call('RPUSH', readyKey, member)
                    end
                end
                return stuck
                """;
        DefaultRedisScript<List> scriptObj = new DefaultRedisScript<>();
        scriptObj.setScriptText(script);
        scriptObj.setResultType(List.class);
        return scriptObj;
    }

    private DefaultRedisScript<List> buildReserveFromReadyScript() {
        String script = """
                local readyKey = KEYS[1]
                local processingKey = KEYS[2]
                local limit = tonumber(ARGV[1])
                local visibility = tonumber(ARGV[2])
                local result = {}
                for i = 1, limit do
                    local member = redis.call('LPOP', readyKey)
                    if not member then
                        break
                    end
                    redis.call('ZADD', processingKey, visibility, member)
                    table.insert(result, member)
                end
                return result
                """;
        DefaultRedisScript<List> scriptObj = new DefaultRedisScript<>();
        scriptObj.setScriptText(script);
        scriptObj.setResultType(List.class);
        return scriptObj;
    }

    private List<String> toStringList(Object result) {
        if (!(result instanceof List<?> list)) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        for (Object obj : list) {
            out.add(String.valueOf(obj));
        }
        return out;
    }
}
