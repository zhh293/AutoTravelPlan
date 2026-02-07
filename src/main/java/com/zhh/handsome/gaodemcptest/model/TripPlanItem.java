package com.zhh.handsome.gaodemcptest.model;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class TripPlanItem {
    private String city;
    private String title;
    private Double lat;
    private Double lng;
}
