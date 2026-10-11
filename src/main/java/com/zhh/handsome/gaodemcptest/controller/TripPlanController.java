package com.zhh.handsome.gaodemcptest.controller;

import com.zhh.handsome.gaodemcptest.model.TripPlanItem;
import com.zhh.handsome.gaodemcptest.service.TripPlanServiceImpl;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/trip")
public class TripPlanController {
    @Autowired
    private TripPlanServiceImpl tripPlanService;

    @PostMapping(value = "/stream-plan", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter streamPlan(
            @RequestBody TripPlanItem tripPlanItem,
            @RequestParam(value = "days", defaultValue = "3") int days,
            @RequestParam(value = "preference", required = false) String preference,
            @RequestParam(value = "taskId", required = false) String taskId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/event-stream;charset=UTF-8");
        if (days < 1)
            days = 1;
        if (days > 10)
            days = 10;
        return tripPlanService.streamPlan(tripPlanItem, days, preference, taskId, lastEventId);
    }
}
