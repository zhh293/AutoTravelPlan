package com.zhh.handsome.gaodemcptest.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LocationResult {
    /**
     * 识别来源：EXIF, BAIDU, PICARTA
     */
    private String source;

    /**
     * 详细地址
     */
    private String formattedAddress;

    /**
     * 国家
     */
    private String country;

    /**
     * 省份
     */
    private String province;

    /**
     * 城市
     */
    private String city;

    /**
     * 区县
     */
    private String district;

    /**
     * 经度
     */
    private Double longitude;

    /**
     * 纬度
     */
    private Double latitude;

    /**
     * 置信度 (0-1)
     */
    private Double confidence;

    /**
     * 识别到的地标名称 (如果适用)
     */
    private String landmarkName;
}
