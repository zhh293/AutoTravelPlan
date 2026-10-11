package com.zhh.handsome.gaodemcptest.async;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class TravelPlanTaskProducer {

    private static final String QUEUE_KEY = "travel-plan:queue";
    private final RedisTemplate<String, Object> redisTemplate;
    private final TaskStateStore taskStateStore;
    private final TaskTimeoutQueue taskTimeoutQueue;
    @Value("${timeout.queue.task-timeout-millis:600000}")
    private long taskTimeoutMillis;

    public String submitTask(Double lng, Double lat, String landmarkName) {
        String taskId = UUID.randomUUID().toString();
        TravelPlanTaskMessage msg = new TravelPlanTaskMessage();
        msg.setTaskId(taskId);
        msg.setLng(lng);
        msg.setLat(lat);
        msg.setLandmarkName(landmarkName);
        msg.setCreatedAt(System.currentTimeMillis());
        taskStateStore.init(taskId);
        taskStateStore.transition(taskId, TaskAction.ENQUEUE);
        taskTimeoutQueue.add(taskId, System.currentTimeMillis() + taskTimeoutMillis);

        // 添加详细的调试信息
        log.info("准备提交任务: {}", msg);

        // 执行push操作
        Long pushResult = redisTemplate.opsForList().leftPush(QUEUE_KEY, msg);
        log.info("Redis push结果: {}", pushResult);

        // 立即检查队列状态
        Long queueSize = redisTemplate.opsForList().size(QUEUE_KEY);
        log.info("提交出游推荐任务, taskId={}, lng={}, lat={}, 队列大小={}", taskId, lng, lat, queueSize);

        // 验证数据是否真的存在
        if (queueSize != null && queueSize > 0) {
            Object firstElement = redisTemplate.opsForList().index(QUEUE_KEY, 0);
            log.info("队列第一个元素: {}", firstElement);
        }

        return taskId;
    }
}
