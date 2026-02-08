package com.zhh.handsome.gaodemcptest.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class PicartaAiUtils {

    @Value("${picarta.api.token}")
    private String apiToken;

    private final CloseableHttpClient httpClient = HttpClients.createDefault();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 调用 Picarta API 进行视觉定位
     * 
     * @param imageBase64 图片Base64
     * @return 包含 lat, lng, confidence 等信息的 Map
     */
    public Map<String, Object> localize(String imageBase64) {
        return localize(imageBase64, 3, null, null);
    }

    /**
     * 调用 Picarta API 进行视觉定位 (支持高级参数)
     * 
     * @param imageBase64 图片Base64
     * @param topK        返回结果数量 (默认 3)
     * @param countryCode 国家代码 (可选)
     * @param admin1      区域/州名称 (可选)
     */
    public Map<String, Object> localize(String imageBase64, int topK, String countryCode, String admin1) {
        Map<String, Object> result = new HashMap<>();
        try {
            String url = "https://picarta.ai/classify";

            // 构造 JSON Payload
            Map<String, Object> payload = new HashMap<>();
            payload.put("TOKEN", apiToken);
            payload.put("IMAGE", imageBase64);
            payload.put("TOP_K", topK);

            if (countryCode != null) {
                payload.put("COUNTRY_CODE", countryCode);
            }
            if (admin1 != null) {
                payload.put("ADMIN1", admin1);
            }
            // 可以根据需要添加 Center_LATITUDE 等参数

            String jsonBody = objectMapper.writeValueAsString(payload);

            HttpPost httpPost = new HttpPost(url);
            httpPost.setHeader("Content-Type", "application/json");
            httpPost.setEntity(new StringEntity(jsonBody, ContentType.APPLICATION_JSON));

            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                String responseStr = EntityUtils.toString(response.getEntity());
                log.info("Picarta response: {}", responseStr);

                JsonNode root = objectMapper.readTree(responseStr);

                // 处理可能的返回结构
                JsonNode targetNode = root;

                // 如果是数组，取第一个
                if (root.isArray() && root.size() > 0) {
                    targetNode = root.get(0);
                } else if (root.has("results") && root.path("results").isArray()) {
                    // 如果被包裹在 results 字段中
                    if (root.path("results").size() > 0) {
                        targetNode = root.path("results").get(0);
                    }
                }

                if (targetNode.has("ai_lat") && targetNode.has("ai_lon")) {
                    result.put("success", true);
                    result.put("lat", targetNode.path("ai_lat").asDouble());
                    result.put("lng", targetNode.path("ai_lon").asDouble());
                    result.put("confidence", targetNode.path("confidence").asDouble(0.0));

                    if (targetNode.has("city"))
                        result.put("city", targetNode.path("city").asText());
                    if (targetNode.has("province"))
                        result.put("province", targetNode.path("province").asText());
                    if (targetNode.has("country"))
                        result.put("country", targetNode.path("country").asText());
                } else {
                    result.put("success", false);
                    result.put("msg", "Picarta 无法定位或未返回有效坐标");
                }
            }
        } catch (Exception e) {
            log.error("Picarta API 调用失败", e);
            result.put("success", false);
            result.put("msg", e.getMessage());
        }
        return result;
    }
}
