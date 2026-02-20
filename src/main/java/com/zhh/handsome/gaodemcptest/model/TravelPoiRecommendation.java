package com.zhh.handsome.gaodemcptest.model;

import lombok.Data;

import java.util.List;

@Data
public class TravelPoiRecommendation {

    private double latitude;
    private double longitude;
    private List<Item> items;

    @Data
    public static class Item {
        private String poiName;
        private String address;
        private String scene;
        private String bestVisitTime;
        private String recommendation;
        private String caption;
        private String url;
    }
}

