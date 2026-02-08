package com.zhh.handsome.gaodemcptest.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class AmapUtils {

    @Value("${amap.rest.key}")
    private String amapKey;

    private final CloseableHttpClient httpClient = HttpClients.createDefault();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 逆地理编码 (经纬度 -> 地址)
     * 适用于图片地标识别后的地址解析
     * @param lng 经度
     * @param lat 纬度
     * @return 地址详情 Map
     */
    public Map<String, Object> regeo(double lng, double lat) {
        Map<String, Object> result = new HashMap<>();
        try {
            URIBuilder uriBuilder = new URIBuilder("https://restapi.amap.com/v3/geocode/regeo");
            uriBuilder.setParameter("location", lng + "," + lat)
                      .setParameter("key", amapKey)
                      .setParameter("extensions", "base") // 基础信息即可
                      .setParameter("radius", "1000")
                      .setParameter("roadlevel", "0");

            URI uri = uriBuilder.build();
            log.info("高德逆地理编码请求: {}", uri);
            
            HttpGet httpGet = new HttpGet(uri);
            
            try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
                String responseStr = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                JsonNode root = objectMapper.readTree(responseStr);
                
                if ("1".equals(root.path("status").asText())) {
                    JsonNode regeo = root.path("regeocode");
                    result.put("success", true);
                    result.put("formatted_address", regeo.path("formatted_address").asText());
                    
                    JsonNode addressComponent = regeo.path("addressComponent");
                    if (!addressComponent.isMissingNode()) {
                        result.put("country", addressComponent.path("country").asText());
                        result.put("province", addressComponent.path("province").asText());
                        result.put("city", addressComponent.path("city").asText());
                        result.put("district", addressComponent.path("district").asText());
                        result.put("township", addressComponent.path("township").asText());
                        result.put("adcode", addressComponent.path("adcode").asText());
                    }
                } else {
                    result.put("success", false);
                    result.put("msg", root.path("info").asText());
                    log.warn("高德API返回错误: {}", root.path("info").asText());
                }
            }
        } catch (Exception e) {
            log.error("高德逆地理编码失败", e);
            result.put("success", false);
            result.put("msg", e.getMessage());
        }
        return result;
    }

    /**
     * 地理编码 (地址 -> 经纬度)
     * @param address 结构化地址
     * @return 经纬度及地址信息 Map
     */
    public Map<String, Object> geo(String address) {
        Map<String, Object> result = new HashMap<>();
        try {
            URIBuilder uriBuilder = new URIBuilder("https://restapi.amap.com/v3/geocode/geo");
            uriBuilder.setParameter("address", address)
                      .setParameter("key", amapKey);

            URI uri = uriBuilder.build();
            HttpGet httpGet = new HttpGet(uri);
            
            try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
                String responseStr = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                JsonNode root = objectMapper.readTree(responseStr);
                
                if ("1".equals(root.path("status").asText()) && root.path("geocodes").isArray() && root.path("geocodes").size() > 0) {
                    JsonNode geocode = root.path("geocodes").get(0);
                    result.put("success", true);
                    result.put("location", geocode.path("location").asText());
                    result.put("formatted_address", geocode.path("formatted_address").asText());
                    result.put("adcode", geocode.path("adcode").asText());
                    result.put("province", geocode.path("province").asText());
                    result.put("city", geocode.path("city").asText());
                    result.put("district", geocode.path("district").asText());
                    
                    String location = geocode.path("location").asText();
                    if (location.contains(",")) {
                        String[] parts = location.split(",");
                        try {
                            result.put("lng", Double.parseDouble(parts[0]));
                            result.put("lat", Double.parseDouble(parts[1]));
                        } catch (NumberFormatException e) {
                            log.warn("坐标解析失败: {}", location);
                        }
                    }
                } else {
                    result.put("success", false);
                    result.put("msg", "未找到地址或API错误: " + root.path("info").asText());
                }
            }
        } catch (Exception e) {
            log.error("高德地理编码失败", e);
            result.put("success", false);
            result.put("msg", e.getMessage());
        }
        return result;
    }
}
