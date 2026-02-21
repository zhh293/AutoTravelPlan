package com.zhh.handsome.gaodemcptest.service;

import com.zhh.handsome.gaodemcptest.model.LocationResult;
import com.zhh.handsome.gaodemcptest.model.Result;
import com.zhh.handsome.gaodemcptest.model.TravelPoiRecommendation;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface ImageLocationService {

    Result<LocationResult> identifyLocation(MultipartFile file);

    SseEmitter identifyLocationWithEffects(MultipartFile file);

    Result<TravelPoiRecommendation> generateTravelPlan(Double lng, Double lat, String landmarkName);
}
