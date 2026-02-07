package com.zhh.handsome.gaodemcptest.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhh.handsome.gaodemcptest.model.PagedResult;
import com.zhh.handsome.gaodemcptest.model.SuggestItem;
import groovy.util.logging.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
@Slf4j
@RefreshScope
@DubboService
public class SearchServiceImpl implements SearchService{
    private static final Logger log = LoggerFactory.getLogger(SearchServiceImpl.class);
    @Value("${amap.rest.key}")
    private String amapKey;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private final CloseableHttpClient httpClient = HttpClients.createDefault();
    private final ObjectMapper mapper = new ObjectMapper();

    public PagedResult<SuggestItem> suggest(String keyword, String city, String types, int page, int size) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        String normalizedCity = city == null ? "" : city.trim();
        String normalizedTypes = types == null ? "" : types.trim();
//        String listKey = buildListCacheKey(normalizedKeyword, normalizedCity, normalizedTypes);
        String pageKey = buildPageCacheKey(normalizedKeyword, normalizedCity, normalizedTypes, page, size);
        PagedResult<SuggestItem> cachedPage = getCachedPage(pageKey);
        log.info("使用缓存：{}", cachedPage != null);
        if (cachedPage != null) {
            cachedPage.setFromCache(true);
            return cachedPage;
        }
        List<SuggestItem> list = getCachedList(pageKey);
        log.info("使用缓存：{}",list);
        if (list == null) {
            list = fetchFromAmap(normalizedKeyword, normalizedCity, normalizedTypes, page, size);
            boolean isHot = markHot(normalizedKeyword);
            int ttlBase = isHot ? 1800 : 300;
            int jitter = ThreadLocalRandom.current().nextInt(30, 120);
            int ttl = ttlBase + jitter;
            if (list.isEmpty()) {
                redisTemplate.opsForValue().set(pageKey, list, Duration.ofSeconds(60));
            } else {
                redisTemplate.opsForValue().set(pageKey, list, Duration.ofSeconds(ttl));
            }
        }
//        PagedResult<SuggestItem> pageResult = slice(list, page, size);
//        boolean isHot = isHot(normalizedKeyword);
//        int ttlBase = isHot ? 900 : 180;
//        int jitter = ThreadLocalRandom.current().nextInt(30, 120);
//        int ttl = ttlBase + jitter;
//        redisTemplate.opsForValue().set(pageKey, pageResult, Duration.ofSeconds(ttl));
        PagedResult<SuggestItem>pageResult=new PagedResult<>();
        pageResult.setPage(page);
        pageResult.setSize(size);
        pageResult.setTotal(list.size());
        pageResult.setItems(list);
        pageResult.setFromCache(false);
        return pageResult;
    }

    private List<SuggestItem> fetchFromAmap(String keyword, String city, String types,int page, int size) {
        if (!StringUtils.hasText(keyword)) {
            return Collections.emptyList();
        }
        log.info("搜索的信息{},{}",keyword,city);
        
        try {
            URIBuilder uriBuilder = new URIBuilder("https://restapi.amap.com/v3/place/text");
            uriBuilder.setParameter("keywords", keyword)
                     .setParameter("city", !StringUtils.hasText(city) ? "全国"
                            : city)
                     .setParameter("children", "1")
                     .setParameter("offset", String.valueOf(size))
                     .setParameter("page", String.valueOf(page))
                     .setParameter("extensions", "all")
                     .setParameter("citylimit", "false")
                     .setParameter("output", "JSON")
                     .setParameter("key", amapKey);
            
            if (StringUtils.hasText(types)) {
                uriBuilder.setParameter("types", types);
            }
            
            URI uri = uriBuilder.build();
            log.info("构建的URI: {}", uri.toString());
            
            HttpGet httpGet = new HttpGet(uri);
            CloseableHttpResponse response = httpClient.execute(httpGet);
            
            try {
                String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                log.info("搜索结果：{}", body);
                
                JsonNode root = mapper.readTree(body);
                if (!"1".equals(root.path("status").asText(""))) {
                    return Collections.emptyList();
                }
                
                JsonNode pois = root.path("pois");
                List<SuggestItem> items = new ArrayList<>();
                if (pois.isArray()) {
                    for (JsonNode poi : pois) {
                        log.info("搜索结果：{}", poi);
                        SuggestItem item = new SuggestItem();
                        item.setId(poi.path("id").asText(""));
                        String name = poi.path("name").asText("");
                        String address = poi.path("address").asText("");
                        String provinceName = poi.path("pname").asText("");
                        String cityName = poi.path("cityname").asText("");
                        String districtName = poi.path("adname").asText("");
                        String adcode = poi.path("adcode").asText("");
                        String location = poi.path("location").asText("");
                        item.setTitle(name);
                        item.setTitleBold(true);
                        item.setProvince(provinceName);
                        item.setCity(cityName);
                        item.setDistrict(districtName);
                        item.setAdcode(adcode);
                        item.setAddress(address);
                        String subtitle = buildSubtitle(provinceName, cityName, districtName, address);
                        item.setSubtitle(subtitle);
                        item.setSubtitleSmall(true);
                        
                        // 解析经纬度
                        if (!location.isEmpty()) {
                            String[] coords = location.split(",");
                            if (coords.length == 2) {
                                try {
                                    item.setLng(Double.parseDouble(coords[0].trim()));
                                    item.setLat(Double.parseDouble(coords[1].trim()));
                                } catch (NumberFormatException e) {
                                    log.warn("经纬度解析失败: {}", location, e);
                                }
                            }
                        }
                        items.add(item);
                    }
                }
                return items;
            } finally {
                response.close();
            }
        } catch (Exception e) {
            log.error("高德地图API调用失败", e);
            return Collections.emptyList();
        }
    }

    private String buildListCacheKey(String keyword, String city, String types) {
        return "search:suggest:list:" + safe(keyword) + ":" + safe(city) + ":" + safe(types);
    }

    private String buildPageCacheKey(String keyword, String city, String types, int page, int size) {
        return "search:suggest:page:" + safe(keyword) + ":" + safe(city) + ":" + safe(types) + ":" + page + ":" + size;
    }

    private String safe(String v) {
        return v == null ? "_" : v.replaceAll("\\s+", "_").toLowerCase();
    }

    private List<SuggestItem> getCachedList(String key) {
        Object obj = redisTemplate.opsForValue().get(key);
        if (obj instanceof List<?>) {
            List<?> list = (List<?>) obj;
            if (!list.isEmpty() && list.get(0) instanceof SuggestItem) {
                @SuppressWarnings("unchecked")
                List<SuggestItem> items = (List<SuggestItem>) list;
                return items;
            }
            if (list.isEmpty()) {
                return Collections.emptyList();
            }
        }
        return null;
    }

    private PagedResult<SuggestItem> getCachedPage(String key) {
        log.info("尝试获取缓存的分页结果{}", key);
        Object obj = redisTemplate.opsForValue().get(key);
        if (obj instanceof PagedResult<?>) {
            @SuppressWarnings("unchecked")
            PagedResult<SuggestItem> pr = (PagedResult<SuggestItem>) obj;
            log.info("获取缓存的分页结果{}成功", pr);
            return pr;
        }
        return null;
    }

    private PagedResult<SuggestItem> slice(List<SuggestItem> list, int page, int size) {
        int total = list.size();
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(total, from + size);
        List<SuggestItem> items = from >= total ? Collections.emptyList() : list.subList(from, to);
        PagedResult<SuggestItem> pr = new PagedResult<>();
        pr.setPage(page);
        pr.setSize(size);
        pr.setTotal(total);
        pr.setItems(new ArrayList<>(items));
        pr.setFromCache(false);
        return pr;
    }

    private boolean markHot(String keyword) {
        String zkey = "search:hot:zset";
        Double score = redisTemplate.opsForZSet().incrementScore(zkey, safe(keyword), 1.0);
        return score != null && score >= 100.0;
    }

    private boolean isHot(String keyword) {
        String zkey = "search:hot:zset";
        Double score = redisTemplate.opsForZSet().score(zkey, safe(keyword));
        return score != null && score >= 100.0;
    }

    private String extractCityFromDistrict(String district) {
        if (!StringUtils.hasText(district))
            return "";
        String[] parts = district.split("\\s+");
        if (parts.length >= 1)
            return parts[0];
        return district;
    }

    private String extractProvinceFromDistrict(String district) {
        if (!StringUtils.hasText(district))
            return "";
        String[] parts = district.split("\\s+");
        if (parts.length >= 2)
            return parts[1];
        return "";
    }

    private String buildSubtitle(String province, String city, String district, String address) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(province))
            sb.append(province);
        if (StringUtils.hasText(city)) {
            if (sb.length() > 0)
                sb.append(" ");
            sb.append(city);
        }
        if (StringUtils.hasText(district)) {
            if (sb.length() > 0)
                sb.append(" ");
            sb.append(district);
        }
        if (StringUtils.hasText(address)) {
            if (sb.length() > 0)
                sb.append(" ");
            sb.append(address);
        }
        return sb.toString();
    }
}
