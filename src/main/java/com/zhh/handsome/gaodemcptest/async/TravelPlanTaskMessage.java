package com.zhh.handsome.gaodemcptest.async;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Data;
import java.io.Serializable;

@Data
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
public class TravelPlanTaskMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    private String taskId;
    private Double lng;
    private Double lat;
    private String landmarkName;
    private long createdAt;
}

