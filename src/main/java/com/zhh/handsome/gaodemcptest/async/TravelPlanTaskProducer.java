package com.zhh.handsome.gaodemcptest.async;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class TravelPlanTaskProducer {

    private static final String QUEUE_KEY = "travel-plan:queue";

    private final RedisTemplate<String, Object> redisTemplate;

    public String submitTask(Double lng, Double lat, String landmarkName) {
        String taskId = UUID.randomUUID().toString();
        TravelPlanTaskMessage msg = new TravelPlanTaskMessage();
        msg.setTaskId(taskId);
        msg.setLng(lng);
        msg.setLat(lat);
        msg.setLandmarkName(landmarkName);
        msg.setCreatedAt(System.currentTimeMillis());
        redisTemplate.opsForList().leftPush(QUEUE_KEY, msg);
        log.info("提交出游推荐任务, taskId={}, lng={}, lat={}", taskId, lng, lat);
        return taskId;
    }
}

