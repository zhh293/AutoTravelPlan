package com.zhh.handsome.gaodemcptest.async;

import lombok.Data;

@Data
public class TravelPlanTaskMessage {

    private String taskId;
    private Double lng;
    private Double lat;
    private String landmarkName;
    private long createdAt;
}

