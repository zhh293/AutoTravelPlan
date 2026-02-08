package com.zhh.handsome.gaodemcptest.service;

import com.drew.lang.GeoLocation;
import com.zhh.handsome.gaodemcptest.model.LocationResult;
import com.zhh.handsome.gaodemcptest.model.Result;
import com.zhh.handsome.gaodemcptest.util.AmapUtils;
import com.zhh.handsome.gaodemcptest.util.BaiduAiUtils;
import com.zhh.handsome.gaodemcptest.util.GpsUtils;
import com.zhh.handsome.gaodemcptest.util.PicartaAiUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.Base64Utils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageLocationServiceImpl implements ImageLocationService {

    private final GpsUtils gpsUtils;
    private final AmapUtils amapUtils;
    private final BaiduAiUtils baiduAiUtils;
    private final PicartaAiUtils picartaAiUtils;

    @Override
    public Result<LocationResult> identifyLocation(MultipartFile file) {
        if (file.isEmpty()) {
            return Result.error("文件为空");
        }

        try {
            // 1. 尝试提取 EXIF GPS 信息
            try (InputStream is = file.getInputStream()) {
                GeoLocation gps = gpsUtils.extractGps(is);
                if (gps != null && !gps.isZero()) {
                    log.info("从图片提取到GPS信息: {}, {}", gps.getLatitude(), gps.getLongitude());
                    Map<String, Object> regeo = amapUtils.regeo(gps.getLongitude(), gps.getLatitude());
                    if ((Boolean) regeo.get("success")) {
                        return Result
                                .success(buildResult("EXIF", gps.getLongitude(), gps.getLatitude(), 1.0, null, regeo));
                    }
                }
            }

            log.info("未提取到有效GPS信息，尝试使用百度AI地标识别");

            // 准备 Base64
            byte[] bytes = file.getBytes();
            String base64 = Base64Utils.encodeToString(bytes);

            // 2. 尝试百度 AI 地标识别
            Map<String, Object> baiduResult = baiduAiUtils.landmark(base64);
            if ((Boolean) baiduResult.get("success")) {
                String landmark = (String) baiduResult.get("landmark");
                log.info("百度AI识别到地标: {}", landmark);
                // 假设识别到地标即为高置信度 (这里简化处理，如果需要阈值判断可扩展 BaiduAiUtils)

                // 调用高德地理编码获取坐标
                Map<String, Object> geo = amapUtils.geo(landmark);
                if ((Boolean) geo.get("success")) {
                    // 百度识别结果本身不带详细地址结构，这里用高德地理编码的结果填充
                    return Result.success(
                            buildResult("BAIDU", (Double) geo.get("lng"), (Double) geo.get("lat"), 0.9, landmark, geo));
                }
            }

            log.info("百度AI未识别到地标或置信度低，尝试Picarta视觉定位");

            // 3. 尝试 Picarta 视觉定位
            Map<String, Object> picartaResult = picartaAiUtils.localize(base64);
            if ((Boolean) picartaResult.get("success")) {
                Double lat = (Double) picartaResult.get("lat");
                Double lng = (Double) picartaResult.get("lng");
                Double confidence = (Double) picartaResult.get("confidence");
                log.info("Picarta定位结果: {}, {}, confidence: {}", lat, lng, confidence);

                // 调用高德逆地理编码获取详细地址
                Map<String, Object> regeo = amapUtils.regeo(lng, lat);
                if ((Boolean) regeo.get("success")) {
                    return Result.success(buildResult("PICARTA", lng, lat, confidence, null, regeo));
                } else {
                    // 如果高德逆地理失败，至少返回坐标
                    return Result.success(LocationResult.builder()
                            .source("PICARTA")
                            .longitude(lng)
                            .latitude(lat)
                            .confidence(confidence)
                            .formattedAddress("未知地址 (坐标: " + lat + ", " + lng + ")")
                            .build());
                }
            }

            return Result.error("无法识别图片地点");

        } catch (Exception e) {
            log.error("图片地点识别失败", e);
            return Result.error("系统异常: " + e.getMessage());
        }
    }

    private LocationResult buildResult(String source, Double lng, Double lat, Double confidence, String landmark,
            Map<String, Object> amapData) {
        return LocationResult.builder()
                .source(source)
                .longitude(lng)
                .latitude(lat)
                .confidence(confidence)
                .landmarkName(landmark)
                .formattedAddress((String) amapData.get("formatted_address"))
                .province((String) amapData.get("province"))
                .city((String) amapData.get("city"))
                .district((String) amapData.get("district"))
                .country((String) amapData.get("country"))
                .build();
    }
}
