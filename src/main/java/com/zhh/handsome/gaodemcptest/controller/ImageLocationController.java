package com.zhh.handsome.gaodemcptest.controller;

import com.zhh.handsome.gaodemcptest.async.TravelPlanTaskProducer;
import com.zhh.handsome.gaodemcptest.model.LocationResult;
import com.zhh.handsome.gaodemcptest.model.Result;
import com.zhh.handsome.gaodemcptest.model.TravelPoiRecommendation;
import com.zhh.handsome.gaodemcptest.service.ImageLocationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/image")
@RequiredArgsConstructor
public class ImageLocationController {

    private final ImageLocationService imageLocationService;
    private final TravelPlanTaskProducer travelPlanTaskProducer;

    @PostMapping("/identify-location")
    public Result<LocationResult> identifyLocation(@RequestParam("file") MultipartFile file) {
        return imageLocationService.identifyLocation(file);
    }

    @PostMapping(value = "/identify-location-with-effects", produces = "text/event-stream")
    public SseEmitter identifyLocationWithEffects(@RequestParam("file") MultipartFile file) {
        return imageLocationService.identifyLocationWithEffects(file);
    }

    @PostMapping("/travel-plan")
    public Result<TravelPoiRecommendation> generateTravelPlan(@RequestParam("lng") Double lng,
            @RequestParam("lat") Double lat,
            @RequestParam(value = "landmarkName", required = false) String landmarkName) {
        return imageLocationService.generateTravelPlan(lng, lat, landmarkName);
    }

    @PostMapping("/async-travel-plan")
    public Result<String> asyncTravelPlan(@RequestParam("lng") Double lng,
            @RequestParam("lat") Double lat,
            @RequestParam(value = "landmarkName", required = false) String landmarkName) {
        String taskId = travelPlanTaskProducer.submitTask(lng, lat, landmarkName);
        return Result.success(taskId);
    }
}
