package com.zhh.handsome.gaodemcptest.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.common.logger.LoggerFactory;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

@Slf4j
@Component
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class AmapPoiUtils {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final CloseableHttpClient httpClient = HttpClients.createDefault();

    // 高德API配置
    private static final String AMAP_AROUND_URL = "https://restapi.amap.com/v3/place/around"; // 周边搜索接口
//    private static final String amapKey = "你的高德API密钥"; // 替换为实际密钥
    @Value("${amap.rest.key}")
    private String amapKey;
    private static final int AMAP_OFFSET = 20; // 每页返回数量
    private static final int AMAP_PAGE = 1; // 页码（MVP先取第一页）
    private static final String SCENIC_TYPE = "110000"; // 景点类型编码（高德POI分类）
    private static final int SEARCH_RADIUS = 5000; // 搜索半径：5000米（可根据需求调整）

    /**
     * 根据单个景点的经纬度，查询其周边的景点
     * @param lng 目标景点的经度（例如：118.783798）
     * @param lat 目标景点的纬度（例如：32.047235）
     * @return 周边景点列表（包含名称、经纬度、地址、评分、图片等）
     */
    public  List<Map<String, Object>> getScenicSpotsAround(Double lng, Double lat) {
        List<Map<String, Object>> spotList = new ArrayList<>();
        // 校验经纬度有效性
        if (lng == null || lat == null || lng < -180 || lng > 180 || lat < -90 || lat > 90) {
            log.warn("目标景点经纬度无效：lng={}, lat={}", lng, lat);
            return spotList;
        }

        try {
            // 构建周边搜索URI（核心替换为place/around接口）
            URIBuilder uriBuilder = new URIBuilder(AMAP_AROUND_URL);
            uriBuilder.setParameter("location", lng + "," + lat) // 核心参数：中心点经纬度
                    .setParameter("radius", String.valueOf(SEARCH_RADIUS)) // 搜索半径（米）
                    .setParameter("keywords", "景点") // 关键词过滤（确保只搜景点）
                    .setParameter("types", SCENIC_TYPE) // 景点类型编码，精准过滤
                    .setParameter("key", amapKey)
                    .setParameter("offset", String.valueOf(AMAP_OFFSET))
                    .setParameter("page", String.valueOf(AMAP_PAGE))
                    .setParameter("extensions", "all") // 返回完整信息（包含评分、图片等）
                    .setParameter("sortrule", "distance") // 按距离由近到远排序（核心优化）
                    .setParameter("output", "JSON");

            URI uri = uriBuilder.build();
            log.info("构建的周边景点查询URI: {}", uri.toString());

            HttpGet httpGet = new HttpGet(uri);
            CloseableHttpResponse response = httpClient.execute(httpGet);

            try {
                String responseString = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                JsonNode root = mapper.readTree(responseString);

                // 校验API返回状态
                if (!"1".equals(root.path("status").asText())) {
                    log.warn("高德周边景点API返回失败，状态：{}，信息：{}",
                            root.path("status").asText(), root.path("info").asText());
                    return spotList;
                }

                // 解析周边景点列表
                JsonNode pois = root.path("pois");
                if (pois.isArray()) {
                    for (JsonNode poi : pois) {
                        String name = poi.path("name").asText("");
                        String location = poi.path("location").asText("");
                        String address = poi.path("address").asText("");
                        String rating = poi.path("rating").asText("");
                        JsonNode photos = poi.path("photos");
                        String photoUrl = !photos.isEmpty() ? photos.get(0).path("url").asText("") : "";

                        // 过滤无效经纬度（保持原有逻辑）
                        if (!StringUtils.hasText(location) || !location.contains(",")) {
                            log.warn("周边景点[{}]经纬度无效：{}", name, location);
                            continue;
                        }

                        String[] lngLat = location.split(",");
                        Double poiLng = parseDouble(lngLat[0]);
                        Double poiLat = parseDouble(lngLat[1]);
                        if (poiLng == null || poiLat == null) {
                            log.warn("周边景点[{}]经纬度解析失败：{}", name, location);
                            continue;
                        }

                        // 组装返回数据（保持原有字段结构）
                        Map<String, Object> spot = new HashMap<>();
                        spot.put("name", name);
                        spot.put("lng", poiLng);
                        spot.put("lat", poiLat);
                        spot.put("address", StringUtils.hasText(address) ? address : "暂无地址");
                        spot.put("rating", StringUtils.hasText(rating) ? rating : "暂无评分");
                        spot.put("url", photoUrl);
                        spotList.add(spot);
                    }
                }
            } finally {
                response.close();
            }
        } catch (Exception e) {
            log.error("调用高德周边景点API失败（中心点：lng={}, lat={}）", lng, lat, e);
        }
        log.info("获取周边景点成功，中心点[{},{}]，周边景点数量：{}", lng, lat, spotList.size());
        log.info("周边景点详情：{}", spotList);
        return spotList;
    }

    // 辅助方法：字符串转Double（保持原有逻辑）
    private  Double parseDouble(String str) {
        try {
            return Double.parseDouble(str);
        } catch (NumberFormatException e) {
            return null;
        }
    }

}
