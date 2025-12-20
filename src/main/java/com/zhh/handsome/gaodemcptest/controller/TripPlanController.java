package com.zhh.handsome.gaodemcptest.controller;

import com.zhh.handsome.gaodemcptest.service.TripPlanService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/trip")
public class TripPlanController {
    @Autowired
    private TripPlanService tripPlanService;

    @GetMapping("/stream-plan")
    public SseEmitter streamPlan(
            @RequestParam("city") String city,
            @RequestParam(value = "days", defaultValue = "3") int days,
            @RequestParam(value = "preference", required = false) String preference
    ) {
        if (days < 1) days = 1;
        if (days > 10) days = 10;
        return tripPlanService.streamPlan(city, days, preference);
    }
}
