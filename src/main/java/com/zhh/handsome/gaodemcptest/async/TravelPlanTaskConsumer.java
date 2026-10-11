package com.zhh.handsome.gaodemcptest.async;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhh.handsome.gaodemcptest.model.AmapPoiUtils;
import com.zhh.handsome.gaodemcptest.model.LocationCouncilPrompt;
import com.zhh.handsome.gaodemcptest.model.LocationLlmCouncil;
import com.zhh.handsome.gaodemcptest.model.TravelPoiRecommendation;
import com.zhh.handsome.gaodemcptest.websocket.TravelPlanWebSocketSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class TravelPlanTaskConsumer {

    private static final String QUEUE_KEY = "travel-plan:queue";
    private static final int WORKER_COUNT = Runtime.getRuntime().availableProcessors() * 2;

    private final RedisTemplate<String, Object> redisTemplate;
    private final AmapPoiUtils amapPoiUtils;
    private final LocationLlmCouncil locationLlmCouncil;
    private final TravelPlanWebSocketSender webSocketSender;
    private final TaskStateStore taskStateStore;
    private final TaskTimeoutQueue taskTimeoutQueue;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void start() {
        for (int i = 0; i < WORKER_COUNT; i++) {
            Thread t = new Thread(this::loop);
            t.setName("travel-plan-consumer-" + i);
            t.setDaemon(true);
            t.start();
        }
    }

    private void loop() {
        while (true) {
            try {
                Object obj = redisTemplate.opsForList().rightPop(QUEUE_KEY, Duration.ofSeconds(5));
                // log.info("获取到outsourcing-travel-plan任务,{}", obj);
                // log.info("obj的实际类型: {}", obj != null ? obj.getClass().getName() : "null");
                if (obj == null) {
                    continue;
                }
                // 处理Redis中存储的任务消息
                TravelPlanTaskMessage msg;
                if (obj instanceof TravelPlanTaskMessage) {
                    // 如果已经是TravelPlanTaskMessage对象，直接使用
                    msg = (TravelPlanTaskMessage) obj;
                    log.info("直接使用TravelPlanTaskMessage对象, taskId={}", msg.getTaskId());
                } else if (obj instanceof java.util.Map) {
                    // 如果是Map类型，尝试转换
                    try {
                        msg = objectMapper.convertValue(obj, TravelPlanTaskMessage.class);
                        log.info("从Map转换为TravelPlanTaskMessage对象, taskId={}", msg.getTaskId());
                    } catch (Exception e) {
                        log.error("无法将Map转换为TravelPlanTaskMessage: {}", obj, e);
                        continue;
                    }
                } else {
                    log.warn("未知的任务消息类型: {}, 内容: {}", obj.getClass(), obj);
                    continue;
                }
                log.info("开始处理outsourcing-travel-plan任务, taskId={}", msg.getTaskId());
                handleTask(msg);
            } catch (Exception e) {
                log.error("处理出游推荐任务时发生异常", e);
            }
        }
    }

    private void handleTask(TravelPlanTaskMessage msg) {
        String taskId = msg.getTaskId();
        log.info("开始处理outsourcing-travel-plan任务, taskId={}", taskId);
        try {
            TaskState currentState = taskStateStore.get(taskId);
            if (currentState == TaskState.EXPIRED) {
                return;
            }
            taskStateStore.transition(taskId, TaskAction.START);
            TravelPoiRecommendation recommendation = buildTravelPlan(msg.getLng(), msg.getLat(), msg.getLandmarkName());
            if (recommendation == null) {
                taskStateStore.transition(taskId, TaskAction.FAIL);
                webSocketSender.sendError(taskId, "多 AI 出游推荐生成失败");
                taskTimeoutQueue.cancel(taskId);
                return;
            }
            taskStateStore.transition(taskId, TaskAction.PUSH);
            webSocketSender.sendPayload(taskId, recommendation);
            taskStateStore.transition(taskId, TaskAction.FINISH);
            taskTimeoutQueue.cancel(taskId);
            log.info("outsourcing-travel-plan任务处理完成, taskId={}", taskId);
        } catch (Exception e) {
            log.error("执行多模型出游推荐失败, taskId={}", taskId, e);
            taskStateStore.transition(taskId, TaskAction.FAIL);
            webSocketSender.sendError(taskId, e.getMessage());
            taskTimeoutQueue.cancel(taskId);
        }
    }

    private TravelPoiRecommendation buildTravelPlan(Double lng, Double lat, String landmarkName) {
        try {
            List<Map<String, Object>> spots = amapPoiUtils.getScenicSpotsAround(lng, lat);
            String poiListText = buildPoiListText(spots);
            String councilPrompt = LocationCouncilPrompt.build(lat, lng, poiListText, null, null, landmarkName);
            String councilResult = locationLlmCouncil.runFullCouncil(councilPrompt);
            log.info("多 AI 讨论结果: {}", councilResult);
            if (councilResult == null) {
                return null;
            }
            String trimmed = councilResult.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            char first = trimmed.charAt(0);
            if (first != '{' && first != '[') {
                return null;
            }
            return objectMapper.readValue(trimmed, TravelPoiRecommendation.class);
        } catch (Exception e) {
            log.error("多 AI 出游推荐内部异常", e);
            return null;
        }
    }

    private String buildPoiListText(List<Map<String, Object>> spots) {
        if (spots == null || spots.isEmpty()) {
            return "暂无可用 POI 信息（如果你无法做出可靠判断，请在输出中显式说明，且不要虚构景点）。";
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> spot : spots) {
            String name = String.valueOf(spot.getOrDefault("name", ""));
            String address = String.valueOf(spot.getOrDefault("address", ""));
            Object lngObj = spot.get("lng");
            Object latObj = spot.get("lat");
            String rating = String.valueOf(spot.getOrDefault("rating", ""));
            String url = String.valueOf(spot.getOrDefault("url", ""));
            sb.append("名称: ").append(name).append("\n")
                    .append("地址: ").append(address).append("\n")
                    .append("经度: ").append(lngObj).append("\n")
                    .append("纬度: ").append(latObj).append("\n")
                    .append("评分: ").append(rating).append("\n")
                    .append("图片: ").append(url).append("\n\n");
        }
        return sb.toString();
    }
}
