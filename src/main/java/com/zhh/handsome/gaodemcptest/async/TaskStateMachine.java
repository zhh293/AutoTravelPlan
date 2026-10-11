package com.zhh.handsome.gaodemcptest.async;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

@Component
public class TaskStateMachine {

    private final Map<TaskState, Map<TaskAction, TaskState>> transitions = new EnumMap<>(TaskState.class);

    public TaskStateMachine() {
        transitions.put(TaskState.CREATED, Map.of(TaskAction.ENQUEUE, TaskState.QUEUED));
        transitions.put(TaskState.QUEUED, Map.of(TaskAction.START, TaskState.RUNNING, TaskAction.TIMEOUT, TaskState.EXPIRED));
        transitions.put(TaskState.RUNNING, Map.of(TaskAction.PUSH, TaskState.PUSHING, TaskAction.FAIL, TaskState.ERROR));
        transitions.put(TaskState.PUSHING, Map.of(TaskAction.FINISH, TaskState.DONE, TaskAction.FAIL, TaskState.ERROR));
    }

    public TaskState next(TaskState current, TaskAction action) {
        if (current == null || action == null) {
            return null;
        }
        Map<TaskAction, TaskState> map = transitions.get(current);
        if (map == null) {
            return null;
        }
        return map.get(action);
    }
}
