package com.zhh.handsome.gaodemcptest.controller;

import com.zhh.handsome.gaodemcptest.async.TravelPlanTaskProducer;
import com.zhh.handsome.gaodemcptest.util.RedisDiagnostics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/debug")
@RequiredArgsConstructor
@Slf4j
public class DebugController {

    private final TravelPlanTaskProducer taskProducer;
    private final RedisDiagnostics redisDiagnostics;

    @PostMapping("/submit-task")
    public Map<String, Object> submitTask(@RequestParam Double lng,
                                         @RequestParam Double lat,
                                         @RequestParam(required = false) String landmark) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            log.info("收到调试任务提交请求: lng={}, lat={}, landmark={}", lng, lat, landmark);
            
            String taskId = taskProducer.submitTask(lng, lat, landmark);
            
            result.put("success", true);
            result.put("taskId", taskId);
            result.put("message", "任务提交成功");
            
        } catch (Exception e) {
            log.error("任务提交失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        
        return result;
    }

    @GetMapping("/diagnose-redis")
    public Map<String, Object> diagnoseRedis() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            redisDiagnostics.diagnoseRedisConfiguration();
            result.put("success", true);
            result.put("message", "Redis诊断完成，请查看日志");
        } catch (Exception e) {
            log.error("Redis诊断失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        
        return result;
    }

    @GetMapping("/diagnose-queue")
    public Map<String, Object> diagnoseQueue() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            redisDiagnostics.diagnoseQueue("travel-plan:queue");
            result.put("success", true);
            result.put("message", "队列诊断完成，请查看日志");
        } catch (Exception e) {
            log.error("队列诊断失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        
        return result;
    }
}