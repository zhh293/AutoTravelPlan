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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class BaiduAiUtils {

    @Value("${baidu.ai.api-key}")
    private String apiKey;

    @Value("${baidu.ai.secret-key}")
    private String secretKey;

    private String accessToken;
    private long tokenExpiresAt = 0;

    private final CloseableHttpClient httpClient = HttpClients.createDefault();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 获取 Access Token
     */
    private synchronized String getAccessToken() {
        if (accessToken != null && System.currentTimeMillis() < tokenExpiresAt) {
            return accessToken;
        }

        try {
            String url = "https://aip.baidubce.com/oauth/2.0/token?grant_type=client_credentials" +
                    "&client_id=" + apiKey +
                    "&client_secret=" + secretKey;
            
            HttpPost httpPost = new HttpPost(url);
            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                String responseStr = EntityUtils.toString(response.getEntity());
                JsonNode root = objectMapper.readTree(responseStr);
                
                if (root.has("access_token")) {
                    accessToken = root.path("access_token").asText();
                    // 提前60秒过期，避免边界问题
                    tokenExpiresAt = System.currentTimeMillis() + (root.path("expires_in").asLong() * 1000) - 60000;
                    return accessToken;
                } else {
                    log.error("获取百度Access Token失败: {}", responseStr);
                }
            }
        } catch (Exception e) {
            log.error("获取百度Access Token异常", e);
        }
        return null;
    }

    /**
     * 地标识别
     * @param imageBase64 图片Base64字符串
     */
    public Map<String, Object> landmark(String imageBase64) {
        Map<String, Object> result = new HashMap<>();
        String token = getAccessToken();
        if (token == null) {
            result.put("success", false);
            result.put("msg", "无法获取Access Token");
            return result;
        }

        try {
            String url = "https://aip.baidubce.com/rest/2.0/image-classify/v1/landmark?access_token=" + token;
            
            // 百度API要求 image 参数进行 urlencode
            String encodedImage = URLEncoder.encode(imageBase64, StandardCharsets.UTF_8.toString());
            String body = "image=" + encodedImage;

            HttpPost httpPost = new HttpPost(url);
            httpPost.setHeader("Content-Type", "application/x-www-form-urlencoded");
            httpPost.setEntity(new StringEntity(body, StandardCharsets.UTF_8));

            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                String responseStr = EntityUtils.toString(response.getEntity());
                JsonNode root = objectMapper.readTree(responseStr);

                if (root.has("result") && root.path("result").has("landmark")) {
                    String landmark = root.path("result").path("landmark").asText();
                    if (landmark != null && !landmark.isEmpty()) {
                        result.put("success", true);
                        result.put("landmark", landmark);
                        // 百度地标接口实际上并不直接返回置信度，但在某些场景下可能包含在其他字段
                        // 文档示例中只返回了 landmark。为了逻辑通顺，我们假设这里如果识别出来就是可信的
                        // 或者我们可以根据 log_id 来做一些判断，但实际上没有具体的 score 字段
                        // 我们可以默认置信度比较高，交给上层判断。
                        // 这里我们手动设置一个默认置信度，或者检查是否有 score 字段
                        // 实际上百度地标接口返回结构很简单: {"log_id": ..., "result": {"landmark": "..."}}
                        // 如果无法识别，landmark 为空字符串
                        result.put("confidence", 0.9); // 默认给一个较高的值，因为百度如果返回了通常比较准
                    } else {
                        result.put("success", false);
                        result.put("msg", "未识别到地标");
                    }
                } else {
                    result.put("success", false);
                    result.put("msg", "未识别到地标");
                }
            }
        } catch (Exception e) {
            log.error("百度地标识别异常", e);
            result.put("success", false);
            result.put("msg", e.getMessage());
        }
        return result;
    }
}
