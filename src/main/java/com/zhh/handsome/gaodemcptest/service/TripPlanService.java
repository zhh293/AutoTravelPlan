package com.zhh.handsome.gaodemcptest.service;

import com.zhh.handsome.gaodemcptest.model.TripPlanItem;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface TripPlanService {
    public SseEmitter streamPlan(TripPlanItem tripPlanItem, int days, String preference);
}
