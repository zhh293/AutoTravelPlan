package com.zhh.handsome.gaodemcptest.service;

import com.drew.lang.GeoLocation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhh.handsome.gaodemcptest.model.AmapPoiUtils;
import com.zhh.handsome.gaodemcptest.model.LocationCouncilPrompt;
import com.zhh.handsome.gaodemcptest.model.LocationLlmCouncil;
import com.zhh.handsome.gaodemcptest.model.LocationResult;
import com.zhh.handsome.gaodemcptest.model.Result;
import com.zhh.handsome.gaodemcptest.model.TravelPoiRecommendation;
import com.zhh.handsome.gaodemcptest.util.AmapUtils;
import com.zhh.handsome.gaodemcptest.util.BaiduAiUtils;
import com.zhh.handsome.gaodemcptest.util.GpsUtils;
import com.zhh.handsome.gaodemcptest.util.PicartaAiUtils;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.Base64Utils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import reactor.core.scheduler.Schedulers;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageLocationServiceImpl implements ImageLocationService {

    private final GpsUtils gpsUtils;
    private final AmapUtils amapUtils;
    private final BaiduAiUtils baiduAiUtils;
    private final PicartaAiUtils picartaAiUtils;
    private final RedisTemplate<String, Object> redisTemplate;
    private final AmapPoiUtils amapPoiUtils;
    private final LocationLlmCouncil locationLlmCouncil;

    @Value("${baidu.ai.api-key}")
    private String baiduApiKey;

    @Value("${baidu.ai.secret-key}")
    private String baiduSecretKey;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile String accessToken;
    private volatile long tokenExpiresAt;

    private static final long MAX_IMAGE_SIZE = 10L * 1024 * 1024;

    private final WebClient webClient = WebClient.builder().build();

    private static final ExecutorService IMAGE_EXECUTOR = new ThreadPoolExecutor(
            8,
            16,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(200),
            r -> {
                Thread t = new Thread(r);
                t.setName("image-enhance-pool-" + t.getId());
                t.setDaemon(true);
                return t;
            });

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
                    Double geoLng = (Double) geo.get("lng");
                    Double geoLat = (Double) geo.get("lat");
                    LocationResult location = buildResult("BAIDU", geoLng, geoLat, 0.9, landmark, geo);
                    TravelPoiRecommendation travelPlan = buildTravelPlan(geoLng, geoLat);
                    if (travelPlan != null) {
                        location.setTravelPlan(travelPlan);
                    }
                    return Result.success(location);
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
                    LocationResult location = buildResult("PICARTA", lng, lat, confidence, null, regeo);
                    TravelPoiRecommendation travelPlan = buildTravelPlan(lng, lat);
                    if (travelPlan != null) {
                        location.setTravelPlan(travelPlan);
                    }
                    return Result.success(location);
                } else {
                    // 如果高德逆地理失败，至少返回坐标
                    LocationResult location = LocationResult.builder()
                            .source("PICARTA")
                            .longitude(lng)
                            .latitude(lat)
                            .confidence(confidence)
                            .formattedAddress("未知地址 (坐标: " + lat + ", " + lng + ")")
                            .build();
                    TravelPoiRecommendation travelPlan = buildTravelPlan(lng, lat);
                    if (travelPlan != null) {
                        location.setTravelPlan(travelPlan);
                    }
                    return Result.success(location);
                }
            }

            return Result.error("无法识别图片地点");

        } catch (Exception e) {
            log.error("图片地点识别失败", e);
            return Result.error("系统异常: " + e.getMessage());
        }
    }

    public SseEmitter identifyLocationWithEffects(MultipartFile file) {
        SseEmitter emitter = new SseEmitter(0L);
        if (file.isEmpty()) {
            try {
                emitter.send(SseEmitter.event().name("error").data(Result.error("文件为空")));
            } catch (Exception ignored) {
            }
            emitter.complete();
            return emitter;
        }

        RedisTokenBucket bucket = new RedisTokenBucket(redisTemplate, "image:effects:bucket", 50, 1);
        if (!bucket.tryAcquire()) {
            try {
                emitter.send(SseEmitter.event().name("error").data(Result.error("请求过于频繁，请稍后重试")));
            } catch (Exception ignored) {
            }
            emitter.complete();
            return emitter;
        }

        CompletableFuture.runAsync(() -> {
            try {
                Result<LocationResult> locationResult = identifyLocation(file);
                emitter.send(SseEmitter.event().name("location").data(locationResult));

                if (locationResult.getData() != null
                        && locationResult.getData().getTravelPlan() != null) {
                    emitter.send(SseEmitter.event().name("travel-plan")
                            .data(locationResult.getData().getTravelPlan()));
                }

                byte[] originalBytes = file.getBytes();
                byte[] processedBytes = ensureMaxSize(originalBytes);
                String base64 = Base64.getEncoder().encodeToString(processedBytes);

                CompletableFuture<ProcessResult> enhanceFuture = callBaiduImageApi("contrast_enhance", base64, null)
                        .whenComplete((r, ex) -> {
                            try {
                                if (ex != null) {
                                    emitter.send(SseEmitter.event().name("enhance")
                                            .data(errorProcessResult("contrast_enhance", ex)));
                                } else {
                                    emitter.send(SseEmitter.event().name("enhance").data(r));
                                }
                            } catch (Exception ignored) {
                            }
                        });

                CompletableFuture<ProcessResult> dehazeFuture = enhanceFuture
                        .thenCompose(prev -> {
                            String inputBase64 = prev != null && prev.isSuccess() && prev.getImageBase64() != null
                                    ? prev.getImageBase64()
                                    : base64;
                            return callBaiduImageApi("dehaze", inputBase64, null)
                                    .whenComplete((r, ex) -> {
                                        try {
                                            if (ex != null) {
                                                emitter.send(SseEmitter.event().name("dehaze")
                                                        .data(errorProcessResult("dehaze", ex)));
                                            } else {
                                                emitter.send(SseEmitter.event().name("dehaze").data(r));
                                            }
                                        } catch (Exception ignored) {
                                        }
                                    });
                        });

                CompletableFuture<Void> privacyFuture = dehazeFuture
                        .thenCompose(prev -> {
                            String inputBase64 = prev != null && prev.isSuccess() && prev.getImageBase64() != null
                                    ? prev.getImageBase64()
                                    : base64;
                            return callBaiduImageApi("selfie_anime", inputBase64, "anime")
                                    .whenComplete((r, ex) -> {
                                        try {
                                            if (ex != null) {
                                                emitter.send(SseEmitter.event().name("privacy")
                                                        .data(errorProcessResult("selfie_anime", ex)));
                                            } else {
                                                emitter.send(SseEmitter.event().name("privacy").data(r));
                                            }
                                        } catch (Exception ignored) {
                                        }
                                    }).thenAccept(r -> {
                                    });
                        });

                privacyFuture.whenComplete((v, ex) -> {
                    try {
                        emitter.complete();
                    } catch (Exception ignored) {
                    }
                });

            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(Result.error("处理失败: " + e.getMessage())));
                } catch (Exception ignored) {
                }
                emitter.complete();
            }
        }, IMAGE_EXECUTOR);

        return emitter;
    }

    private String buildPoiListText(java.util.List<java.util.Map<String, Object>> spots) {
        if (spots == null || spots.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < spots.size(); i++) {
            java.util.Map<String, Object> spot = spots.get(i);
            String name = spot.getOrDefault("name", "").toString();
            String address = spot.getOrDefault("address", "").toString();
            Object lng = spot.get("lng");
            Object lat = spot.get("lat");
            String rating = spot.getOrDefault("rating", "").toString();
            String url = spot.getOrDefault("url", "").toString();
            sb.append("POI ").append(i + 1).append(":\n")
                    .append("名称: ").append(name).append("\n")
                    .append("地址: ").append(address).append("\n")
                    .append("经度: ").append(lng).append("\n")
                    .append("纬度: ").append(lat).append("\n")
                    .append("评分: ").append(rating).append("\n")
                    .append("图片: ").append(url).append("\n\n");
        }
        return sb.toString();
    }

    private TravelPoiRecommendation buildTravelPlan(Double lng, Double lat) {
        try {
            java.util.List<java.util.Map<String, Object>> spots = amapPoiUtils.getScenicSpotsAround(lng, lat);
            String poiListText = buildPoiListText(spots);
            String councilPrompt = LocationCouncilPrompt.build(lat, lng, poiListText, null, null);
            String councilResult = locationLlmCouncil.runFullCouncil(councilPrompt);
            log.info("多 AI 讨论结果: {}", councilResult);
            if (councilResult == null) {
                log.warn("多 AI 讨论未返回结果");
                return null;
            }
            String trimmed = councilResult.trim();
            if (trimmed.isEmpty()) {
                log.warn("多 AI 讨论返回内容为空");
                return null;
            }
            char first = trimmed.charAt(0);
            if (first != '{' && first != '[') {
                log.warn("多 AI 讨论返回的不是 JSON: {}", trimmed);
                return null;
            }
            return objectMapper.readValue(trimmed, TravelPoiRecommendation.class);
        } catch (Exception e) {
            log.error("多 AI 出游推荐内部异常", e);
            return null;
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

    private byte[] ensureMaxSize(byte[] data) {
        if (data.length <= MAX_IMAGE_SIZE) {
            return data;
        }
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(data);
            java.awt.image.BufferedImage image = ImageIO.read(bais);
            if (image == null) {
                return data;
            }
            float quality = 0.9f;
            byte[] result = data;
            while (quality > 0.1f) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
                ImageWriteParam param = writer.getDefaultWriteParam();
                if (param.canWriteCompressed()) {
                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    param.setCompressionQuality(quality);
                }
                ImageOutputStream ios = ImageIO.createImageOutputStream(baos);
                writer.setOutput(ios);
                writer.write(null, new IIOImage(image, null, null), param);
                ios.close();
                writer.dispose();
                result = baos.toByteArray();
                if (result.length <= MAX_IMAGE_SIZE) {
                    break;
                }
                quality -= 0.1f;
            }
            return result;
        } catch (Exception e) {
            return data;
        }
    }

    private synchronized String getAccessToken() {
        long now = System.currentTimeMillis();
        if (accessToken != null && now < tokenExpiresAt) {
            return accessToken;
        }
        try {
            String url = "https://aip.baidubce.com/oauth/2.0/token?grant_type=client_credentials"
                    + "&client_id=" + URLEncoder.encode(baiduApiKey, StandardCharsets.UTF_8.name())
                    + "&client_secret=" + URLEncoder.encode(baiduSecretKey, StandardCharsets.UTF_8.name());
            String responseStr = webClient.post()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            if (responseStr == null) {
                return null;
            }
            JsonNode root = objectMapper.readTree(responseStr);
            if (root.has("access_token")) {
                accessToken = root.path("access_token").asText();
                long expiresIn = root.path("expires_in").asLong() * 1000L;
                tokenExpiresAt = now + expiresIn - 60000L;
                return accessToken;
            }
        } catch (Exception e) {
            log.error("获取百度Access Token失败", e);
        }
        return null;
    }

    private CompletableFuture<ProcessResult> callBaiduImageApi(String apiName, String imageBase64, String type) {
        String token = getAccessToken();
        ProcessResult base = new ProcessResult();
        base.setType(apiName);
        if (token == null) {
            base.setSuccess(false);
            base.setMessage("无法获取access_token");
            return CompletableFuture.completedFuture(base);
        }
        try {
            String url = "https://aip.baidubce.com/rest/2.0/image-process/v1/" + apiName
                    + "?access_token=" + URLEncoder.encode(token, StandardCharsets.UTF_8.name());
            StringBuilder body = new StringBuilder();
            body.append("image=").append(URLEncoder.encode(imageBase64, StandardCharsets.UTF_8.name()));
            if (type != null) {
                body.append("&type=").append(URLEncoder.encode(type, StandardCharsets.UTF_8.name()));
            }
            return webClient.post()
                    .uri(url)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .bodyValue(body.toString())
                    .retrieve()
                    .bodyToMono(String.class)
                    .publishOn(Schedulers.fromExecutor(IMAGE_EXECUTOR))
                    .map(responseStr -> {
                        ProcessResult result = new ProcessResult();
                        result.setType(apiName);
                        if (responseStr == null) {
                            result.setSuccess(false);
                            result.setMessage("调用百度图像处理接口返回为空");
                            return result;
                        }
                        try {
                            JsonNode root = objectMapper.readTree(responseStr);
                            if (root.has("image")) {
                                result.setSuccess(true);
                                result.setImageBase64(root.path("image").asText());
                                result.setMessage("ok");
                            } else {
                                result.setSuccess(false);
                                result.setMessage(responseStr);
                            }
                        } catch (Exception e) {
                            result.setSuccess(false);
                            result.setMessage(e.getMessage());
                        }
                        return result;
                    })
                    .onErrorResume(e -> {
                        log.error("调用百度图像处理接口失败", e);
                        ProcessResult result = new ProcessResult();
                        result.setType(apiName);
                        result.setSuccess(false);
                        result.setMessage(e.getMessage());
                        return reactor.core.publisher.Mono.just(result);
                    })
                    .toFuture();
        } catch (Exception e) {
            log.error("构造百度图像处理请求失败", e);
            base.setSuccess(false);
            base.setMessage(e.getMessage());
            return CompletableFuture.completedFuture(base);
        }
    }

    private ProcessResult errorProcessResult(String type, Throwable ex) {
        ProcessResult r = new ProcessResult();
        r.setType(type);
        r.setSuccess(false);
        r.setMessage(ex.getMessage());
        return r;
    }

    @Data
    private static class ProcessResult {
        private String type;
        private boolean success;
        private String message;
        private String imageBase64;
    }

    private static class RedisTokenBucket {
        private final RedisTemplate<String, Object> redisTemplate;
        private final String key;
        private final int capacity;
        private final int windowSeconds;

        private RedisTokenBucket(RedisTemplate<String, Object> redisTemplate, String key, int capacity,
                int windowSeconds) {
            this.redisTemplate = redisTemplate;
            this.key = key;
            this.capacity = capacity;
            this.windowSeconds = windowSeconds;
        }

        private boolean tryAcquire() {
            Long value = redisTemplate.opsForValue().increment(key);
            if (value != null && value == 1L) {
                redisTemplate.expire(key, windowSeconds, TimeUnit.SECONDS);
            }
            return value != null && value <= capacity;
        }
    }
}
