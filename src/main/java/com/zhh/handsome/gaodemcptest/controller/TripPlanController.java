package com.zhh.handsome.gaodemcptest.controller;

import com.zhh.handsome.gaodemcptest.model.TripPlanItem;
import com.zhh.handsome.gaodemcptest.service.TripPlanServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/trip")
public class TripPlanController {
    @Autowired
    private TripPlanServiceImpl tripPlanService;

    @GetMapping(value = "/stream-plan",produces = "text/event-stream")
    public SseEmitter streamPlan(
            @RequestBody TripPlanItem tripPlanItem,
            @RequestParam(value = "days", defaultValue = "3") int days,
            @RequestParam(value = "preference", required = false) String preference
    ) {
        if (days < 1) days = 1;
        if (days > 10) days = 10;
        return tripPlanService.streamPlan(tripPlanItem, days, preference);
    }
}
