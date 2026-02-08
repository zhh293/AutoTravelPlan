package com.zhh.handsome.gaodemcptest.service;

import com.zhh.handsome.gaodemcptest.model.LocationResult;
import com.zhh.handsome.gaodemcptest.model.Result;
import org.springframework.web.multipart.MultipartFile;

public interface ImageLocationService {
    /**
     * 识别图片地点
     * @param file 上传的图片文件
     * @return 地点识别结果
     */
    Result<LocationResult> identifyLocation(MultipartFile file);
}
