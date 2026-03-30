package com.zhh.handsome.gaodemcptest.async;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TaskStateStore {

    private static final String KEY_PREFIX = "travel-plan:state:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final TaskStateMachine stateMachine;
    private final DefaultRedisScript<Long> transitionScript = buildTransitionScript();

    public TaskState init(String taskId) {
        TaskState state = TaskState.CREATED;
        redisTemplate.opsForValue().set(KEY_PREFIX + taskId, state.name());
        return state;
    }

    public TaskState get(String taskId) {
        Object value = redisTemplate.opsForValue().get(KEY_PREFIX + taskId);
        if (value == null) {
            return null;
        }
        try {
            return TaskState.valueOf(String.valueOf(value));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public void delete(String taskId) {
        if (taskId == null) {
            return;
        }
        redisTemplate.delete(KEY_PREFIX + taskId);
    }

    public TaskState transition(String taskId, TaskAction action) {
        TaskState current = get(taskId);
        TaskState next = stateMachine.next(current, action);
        if (next == null) {
            return current;
        }
        Long updated = redisTemplate.execute(
                transitionScript,
                java.util.List.of(KEY_PREFIX + taskId),
                current == null ? "" : current.name(),
                next.name()
        );
        if (updated != null && updated == 1L) {
            return next;
        }
        return get(taskId);
    }
    //防止并发更新，使用lua脚本原子操作 
    //如果当前状态与预期状态不一致，说明有其他线程更新了状态，直接返回当前状态
    //如果当前状态与预期状态一致，说明当前线程是第一个更新状态，更新状态后返回新状态

    // 幂等 + 并发控制 = 保证状态机“只允许合法的单向流转”，不因重复消息或并发写而乱序。
    private DefaultRedisScript<Long> buildTransitionScript() {
        String script = """
                local key = KEYS[1]
                local expected = ARGV[1]
                local next = ARGV[2]
                local current = redis.call('GET', key)
                if current == false then
                    return 0
                end
                if current ~= expected then
                    return 0
                end
                redis.call('SET', key, next)
                return 1
                """;
        DefaultRedisScript<Long> scriptObj = new DefaultRedisScript<>();
        scriptObj.setScriptText(script);
        scriptObj.setResultType(Long.class);
        return scriptObj;
    }
}
