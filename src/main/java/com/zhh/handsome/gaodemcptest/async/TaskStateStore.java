package com.zhh.handsome.gaodemcptest.async;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TaskStateStore {

    private static final String KEY_PREFIX = "travel-plan:state:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final TaskStateMachine stateMachine;

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
        redisTemplate.opsForValue().set(KEY_PREFIX + taskId, next.name());
        return next;
    }
}
