package com.zhh.handsome.gaodemcptest.async;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class TaskTimeoutQueue {

    private static final String DELAY_ZSET = "travel-plan:timeout:zset";
    private static final String PROCESSING_ZSET = "travel-plan:timeout:processing:zset";
    private static final String READY_LIST = "travel-plan:timeout:ready:list";
    private final RedisTemplate<String, Object> redisTemplate;
    private final DefaultRedisScript<List> moveDueScript = buildMoveDueScript();
    private final DefaultRedisScript<List> reclaimScript = buildReclaimScript();
    private final long visibilityMillis;

    public TaskTimeoutQueue(
            RedisTemplate<String, Object> redisTemplate,
            @Value("${timeout.queue.visibility-millis:30000}") long visibilityMillis) {
        this.redisTemplate = redisTemplate;
        this.visibilityMillis = visibilityMillis;
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
    }

    public List<String> moveDue(long nowMillis, int limit) {
        Object result = redisTemplate.execute(
                moveDueScript,
                List.of(DELAY_ZSET, PROCESSING_ZSET, READY_LIST),
                String.valueOf(nowMillis),
                String.valueOf(limit),
                String.valueOf(nowMillis + visibilityMillis));
        return toStringList(result);
    }

    public List<String> reclaim(long nowMillis, int limit) {
        Object result = redisTemplate.execute(
                reclaimScript,
                List.of(PROCESSING_ZSET, READY_LIST),
                String.valueOf(nowMillis),
                String.valueOf(limit));
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

    public void ack(String taskId) {
        if (taskId == null) {
            return;
        }
        redisTemplate.opsForZSet().remove(PROCESSING_ZSET, taskId);
    }

    private DefaultRedisScript<List> buildMoveDueScript() {
        String script = """
                local delayKey = KEYS[1]
                local processingKey = KEYS[2]
                local readyKey = KEYS[3]
                local now = tonumber(ARGV[1])
                local limit = tonumber(ARGV[2])
                local visibility = tonumber(ARGV[3])
                local due = redis.call('ZRANGEBYSCORE', delayKey, 0, now, 'LIMIT', 0, limit)
                if #due == 0 then
                    return {}
                end
                for i = 1, #due do
                    local member = due[i]
                    redis.call('ZREM', delayKey, member)
                    redis.call('ZADD', processingKey, visibility, member)
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
                local now = tonumber(ARGV[1])
                local limit = tonumber(ARGV[2])
                local stuck = redis.call('ZRANGEBYSCORE', processingKey, 0, now, 'LIMIT', 0, limit)
                if #stuck == 0 then
                    return {}
                end
                for i = 1, #stuck do
                    local member = stuck[i]
                    redis.call('ZREM', processingKey, member)
                    redis.call('RPUSH', readyKey, member)
                end
                return stuck
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
