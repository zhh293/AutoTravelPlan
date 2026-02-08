package com.zhh.handsome.gaodemcptest.controller;

import com.zhh.handsome.gaodemcptest.model.LocationResult;
import com.zhh.handsome.gaodemcptest.model.Result;
import com.zhh.handsome.gaodemcptest.service.ImageLocationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/image")
@RequiredArgsConstructor
public class ImageLocationController {

    private final ImageLocationService imageLocationService;

    /**
     * 图片地点识别
     * @param file 上传的图片
     * @return 识别结果
     */
    @PostMapping("/identify-location")
    public Result<LocationResult> identifyLocation(@RequestParam("file") MultipartFile file) {
        return imageLocationService.identifyLocation(file);
    }
}
