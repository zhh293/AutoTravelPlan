package com.zhh.handsome.gaodemcptest.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.json.JsonParseException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.core.task.TaskExecutor;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class TripPlanService {
    private static final Logger log = LoggerFactory.getLogger(TripPlanService.class);

    // 高德API参数常量（避免硬编码）
    private static final String AMAP_PLACE_URL = "https://restapi.amap.com/v3/place/text";
    private static final String AMAP_DRIVING_URL = "https://restapi.amap.com/v3/direction/driving";
    private static final String SCENIC_TYPE = "110000";
    private static final int AMAP_OFFSET = 20;
    private static final int AMAP_PAGE = 1;
    private static final int ROUTE_STRATEGY = 1; // 最优路线

    // SSE超时时间（3分钟，避免连接挂起）
    private static final long SSE_TIMEOUT = 180_000L;

    @Value("${amap.rest.key}")
    private String amapKey;

    private final CloseableHttpClient httpClient = HttpClients.createDefault();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ChatModel chatModel;
    private final TaskExecutor taskExecutor;

    public TripPlanService(ChatModel chatModel, TaskExecutor tripTaskExecutor) {
        this.chatModel = chatModel;
        this.taskExecutor = tripTaskExecutor;
    }

    public SseEmitter streamPlan(String city, int days, String preference) {
        // 1. 初始化SSE，设置超时时间
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        // 2. 注册SSE回调，避免资源泄漏
        registerSseCallbacks(emitter);

        taskExecutor.execute(() -> {
            try {
                // 校验入参
                if (!StringUtils.hasText(city) || days < 1) {
                    sendError(emitter, "参数错误：城市不能为空，天数需≥1");
                    completeEmitter(emitter);
                    return;
                }

                // 获取城市景点
                List<Map<String, Object>> spots = getCityScenicSpots(city);
                if (spots.isEmpty()) {
                    sendError(emitter, "未获取到[" + city + "]的景点数据");
                    completeEmitter(emitter);
                    return;
                }

                // 天气
                List<Map<String, Object>> weather = getWeatherForecast(city, Math.min(2, days));
                if (!weather.isEmpty()) {
                    Map<String, Object> weatherPayload = new HashMap<>();
                    weatherPayload.put("city", city);
                    weatherPayload.put("days", weather);
                    sendWeather(emitter, weatherPayload);
                }

                // 初始化每日景点/颜色配置
                String[] colors = new String[] { "#FF5733", "#33A1FF", "#33FF57", "#FF33A1", "#FFA533" };
                // 修正：每日景点数=总景点数/天数，避免超出总数
                int desiredPerDay = Math.max(1, Math.min(4, spots.size() / days));
                Map<Integer, List<Map<String, Object>>> daySpots = new ConcurrentHashMap<>();
                Map<Integer, String> dayColor = new ConcurrentHashMap<>();
                for (int d = 1; d <= days; d++) {
                    daySpots.put(d, new ArrayList<>());
                    dayColor.put(d, colors[(d - 1) % colors.length]);
                }

                // 构建AI Prompt
                String prompt = buildPrompt(city, days, preference, spots, dayColor);
                AtomicBoolean done = new AtomicBoolean(false);
                StringBuilder buf = new StringBuilder();
                AtomicInteger consumedLen = new AtomicInteger(0); // 原子整数，避免多线程问题
                List<String> completeJsonList = new ArrayList<>();
                // 替换原有的chatModel.stream.subscribe(...)部分，重点看缓冲区处理和增量解析
                int lastProcessedPosition = 0;
                int lastLeftBracketCount = 0;
                int lastRightBracketCount = 0;
                Map<String, Integer> lastProcessedJson = new HashMap<>();
                lastProcessedJson.put("lastProcessedPosition", lastProcessedPosition);
                lastProcessedJson.put("lastLeftBracketCount", lastLeftBracketCount);
                lastProcessedJson.put("lastRightBracketCount", lastRightBracketCount);
                chatModel.stream(new Prompt(new UserMessage(prompt))).subscribe(
                        // 正常回调：处理AI返回内容（核心重构）
                        resp -> {
                            try {
                                String content = extractContent(resp);
                                if (!StringUtils.hasText(content)) {
                                    return;
                                }

                                // 关键1：累加流式内容到缓冲区（不拆分，只累积）
                                synchronized (buf) {
                                    buf.append(content);
                                    // 关键2：尝试从缓冲区中解析"完整的JSON对象"
                                    Map<String, Integer> map = extractCompleteJson(buf, completeJsonList,
                                            lastProcessedJson.get("lastProcessedPosition"),
                                            lastProcessedJson.get("lastLeftBracketCount"),
                                            lastProcessedJson.get("lastRightBracketCount"));
                                    lastProcessedJson.clear();
                                    lastProcessedJson.putAll(map);
                                    if (!completeJsonList.isEmpty()) {
                                        // 处理每个完整的JSON
                                        for (String completeJson : completeJsonList) {
                                            processAiJsonFragment(completeJson, daySpots, dayColor, desiredPerDay,
                                                    emitter);
                                        }
                                        completeJsonList.clear();
                                    }
                                }
                            } catch (Exception e) {
                                log.error("处理AI流式内容失败", e);
                                sendError(emitter, "处理行程数据失败：" + e.getMessage());
                            }
                        },
                        // 异常回调（不变）
                        ex -> {
                            log.error("AI流式调用失败", ex);
                            sendError(emitter, "行程生成失败：" + ex.getMessage());
                            done.set(true);
                            completeEmitter(emitter);
                        },
                        // 完成回调（不变）
                        () -> {
                            log.info("AI行程生成完成");
                            done.set(true);
                            // 最后检查缓冲区是否有剩余的完整JSON（避免遗漏）
                            synchronized (buf) {
                                if (StringUtils.hasText(buf.toString())) {
                                    // 使用新的括号匹配方法检查剩余的完整JSON
                                    Map<String, Integer> finalState = extractCompleteJson(buf, completeJsonList,
                                        lastProcessedJson.get("lastProcessedPosition"),
                                        lastProcessedJson.get("lastLeftBracketCount"),
                                        lastProcessedJson.get("lastRightBracketCount"));

                                    lastProcessedJson.clear();
                                    lastProcessedJson.putAll(finalState);

                                    if (!completeJsonList.isEmpty()) {
                                        completeJsonList.forEach(json -> processAiJsonFragment(json, daySpots, dayColor,
                                                desiredPerDay, emitter));
                                        completeJsonList.clear();
                                    }
                                }
                            }
                        });

                // 等待异步处理完成
                while (!done.get()) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ignored) {
                    }
                }
                
                // 确保在所有处理完成后才关闭SSE连接
                sendDone(emitter);
                completeEmitter(emitter);
            } catch (Exception e) {
                log.error("行程规划主流程失败", e);
                sendError(emitter, "系统异常：" + e.getMessage());
                // 确保在异常情况下也正确关闭emitter
                try {
                    completeEmitter(emitter);
                } catch (Exception ex) {
                    log.error("关闭SSE连接失败", ex);
                }
            }
        });

        return emitter;
    }

    /**
     * 获取城市景点（高德POI API）
     */
    private List<Map<String, Object>> getCityScenicSpots(String city) {
        List<Map<String, Object>> spotList = new ArrayList<>();
        try {
            URIBuilder uriBuilder = new URIBuilder(AMAP_PLACE_URL);
            uriBuilder.setParameter("keywords", "景点")
                     .setParameter("city", city)
                     .setParameter("types", SCENIC_TYPE)
                     .setParameter("key", amapKey)
                     .setParameter("offset", String.valueOf(AMAP_OFFSET))
                     .setParameter("page", String.valueOf(AMAP_PAGE))
                     .setParameter("extensions", "all")
                     .setParameter("citylimit", "true"); // 添加citylimit参数

            URI uri = uriBuilder.build();
            log.info("构建的景点查询URI: {}", uri.toString());

            HttpGet httpGet = new HttpGet(uri);
            CloseableHttpResponse response = httpClient.execute(httpGet);

            try {
                String responseString = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                JsonNode root = mapper.readTree(responseString);

                // 校验API返回状态
                if (!"1".equals(root.path("status").asText())) {
                    log.warn("高德景点API返回失败，状态：{}，信息：{}", root.path("status").asText(), root.path("info").asText());
                    return spotList;
                }

                JsonNode pois = root.path("pois");
                if (pois.isArray()) {
                    for (JsonNode poi : pois) {
                        String name = poi.path("name").asText("");
                        String location = poi.path("location").asText("");
                        String address = poi.path("address").asText("");
                        String rating = poi.path("rating").asText("");

                        // 过滤无效经纬度
                        if (!StringUtils.hasText(location) || !location.contains(",")) {
                            log.warn("景点[{}]经纬度无效：{}", name, location);
                            continue;
                        }

                        String[] lngLat = location.split(",");
                        Double lng = parseDouble(lngLat[0]);
                        Double lat = parseDouble(lngLat[1]);
                        if (lng == null || lat == null) {
                            log.warn("景点[{}]经纬度解析失败：{}", name, location);
                            continue;
                        }

                        Map<String, Object> spot = new HashMap<>();
                        spot.put("name", name);
                        spot.put("lng", lng);
                        spot.put("lat", lat);
                        spot.put("address", StringUtils.hasText(address) ? address : "暂无地址");
                        spot.put("rating", StringUtils.hasText(rating) ? rating : "暂无评分");
                        spotList.add(spot);
                    }
                }
            } finally {
                response.close();
            }
        } catch (Exception e) {
            log.error("调用高德景点API失败", e);
        }
        log.info("获取城市[{}]景点成功，数量：{}", city, spotList.size());
        log.info("获取城市[{}]景点详情：{}", city, spotList);
        return spotList;
    }

    /**
     * 从缓冲区中增量解析"完整的JSON对象"，剩余内容保留在缓冲区中
     * 核心逻辑：用括号匹配的方式识别完整JSON，未解析的内容保留在缓冲区中
     * @param buf 字符串缓冲区
     * @param completeJsonList 用于存储解析到的完整JSON对象的列表
     * @param lastProcessedPosition 上次处理的位置
     * @param lastLeftBracketCount 上次处理的左括号计数
     * @param lastRightBracketCount 上次处理的右括号计数
     * @return 包含更新后状态的映射
     */
    private Map<String, Integer> extractCompleteJson(StringBuilder buf, List<String> completeJsonList, 
            int lastProcessedPosition, int lastLeftBracketCount, int lastRightBracketCount) {
        Map<String, Integer> result = new HashMap<>();

        if (buf.length() == 0) {
            result.put("lastProcessedPosition", lastProcessedPosition);
            result.put("lastLeftBracketCount", lastLeftBracketCount);
            result.put("lastRightBracketCount", lastRightBracketCount);
            return result;
        }

        // 从上次处理的位置开始
        int i = lastProcessedPosition;
        while (i < buf.length()) {
            char c = buf.charAt(i);
            if (c == '{') {
                lastLeftBracketCount++;
            } else if (c == '}') {
                lastRightBracketCount++;
            }
            
            // 当左右括号数量相等且大于0时，说明找到了一个完整的JSON对象
            if (lastLeftBracketCount == lastRightBracketCount && lastLeftBracketCount > 0) {
                String completeJson = buf.substring(0, i + 1);
                completeJsonList.add(completeJson);
                
                // 删除已处理的部分
                buf.delete(0, i + 1);
                
                // 重置计数器，从头开始处理剩余内容
                lastLeftBracketCount = 0;
                lastRightBracketCount = 0;
                i = -1; // 重置为-1，因为while循环会自增为0
            }
            i++;
        }

        result.put("lastProcessedPosition", i > 0 ? i : 0);
        result.put("lastLeftBracketCount", lastLeftBracketCount);
        result.put("lastRightBracketCount", lastRightBracketCount);
        
        return result;
    }

    /**
     * 校验/补全景点经纬度
     */
    private Map<String, Object> verifyOrCompleteSpot(Map<String, Object> spot, String city) {
        // 经纬度有效则直接返回
        Object lngObj = spot.get("lng");
        Object latObj = spot.get("lat");
        if (lngObj instanceof Double && latObj instanceof Double) {
            return spot;
        }

        String spotName = String.valueOf(spot.getOrDefault("name", ""));
        if (!StringUtils.hasText(spotName)) {
            log.warn("景点名称为空，无法补全经纬度");
            return spot;
        }

        try {
            URIBuilder uriBuilder = new URIBuilder(AMAP_PLACE_URL);
            uriBuilder.setParameter("keywords", spotName)
                     .setParameter("city", city)
                     .setParameter("types", SCENIC_TYPE)
                     .setParameter("key", amapKey)
                     .setParameter("offset", "1")
                     .setParameter("page", "1")
                     .setParameter("extensions", "all")
                     .setParameter("citylimit", "true"); // 添加citylimit参数

            URI uri = uriBuilder.build();
            log.info("构建的景点补全URI: {}", uri.toString());

            HttpGet httpGet = new HttpGet(uri);
            CloseableHttpResponse response = httpClient.execute(httpGet);

            try {
                String responseString = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                JsonNode root = mapper.readTree(responseString);
                JsonNode pois = root.path("pois");

                if (pois.isArray() && pois.size() > 0) {
                    JsonNode poi = pois.get(0);
                    String location = poi.path("location").asText("");
                    String address = poi.path("address").asText("");

                    if (StringUtils.hasText(location) && location.contains(",")) {
                        String[] lngLat = location.split(",");
                        Double lng = parseDouble(lngLat[0]);
                        Double lat = parseDouble(lngLat[1]);
                        if (lng != null && lat != null) {
                            spot.put("lng", lng);
                            spot.put("lat", lat);
                            // 补全地址（若为空）
                            if (!StringUtils.hasText(String.valueOf(spot.getOrDefault("address", "")))) {
                                spot.put("address", StringUtils.hasText(address) ? address : "暂无地址");
                            }
                        }
                    }
                }
            } finally {
                response.close();
            }
        } catch (Exception e) {
            log.error("补全景点[{}]经纬度失败", spotName, e);
        }
        return spot;
    }

    /**
     * 获取最优路线（高德驾车路线API）
     */
    private List<Map<String, Double>> getOptimalRoute(List<Map<String, Object>> spots) {
        List<Map<String, Double>> routePoints = new ArrayList<>();
        if (spots == null || spots.size() < 2) {
            log.warn("路线规划失败：景点数不足（需≥2）");
            return routePoints;
        }

        // 构建途经点（高德API要求：经纬度用;分隔）
        String waypoints = buildWaypoints(spots);
        if (!StringUtils.hasText(waypoints)) {
            log.warn("路线规划失败：有效经纬度为空");
            return routePoints;
        }

        String[] waypointArray = waypoints.split(";");
        String origin = waypointArray[0];
        String destination = waypointArray[waypointArray.length - 1];

        try {
            URIBuilder uriBuilder = new URIBuilder(AMAP_DRIVING_URL);
            uriBuilder.setParameter("origin", origin)
                     .setParameter("destination", destination)
                     .setParameter("waypoints", waypoints)
                     .setParameter("key", amapKey)
                     .setParameter("strategy", String.valueOf(ROUTE_STRATEGY));

            URI uri = uriBuilder.build();
            log.info("构建的路线规划URI: {}", uri.toString());

            HttpGet httpGet = new HttpGet(uri);
            CloseableHttpResponse response = httpClient.execute(httpGet);

            try {
                String responseString = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                JsonNode root = mapper.readTree(responseString);

                // 校验路线API返回状态
                if (!"1".equals(root.path("status").asText())) {
                    log.warn("高德路线API返回失败，状态：{}，信息：{}", root.path("status").asText(), root.path("info").asText());
                    return routePoints;
                }

                JsonNode paths = root.path("route").path("paths");
                if (paths.isArray() && paths.size() > 0) {
                    JsonNode steps = paths.get(0).path("steps");
                    for (JsonNode step : steps) {
                        String polyline = step.path("polyline").asText("");
                        if (StringUtils.hasText(polyline)) {
                            String[] points = polyline.split(";");
                            for (String point : points) {
                                String[] lngLat = point.split(",");
                                Double lng = parseDouble(lngLat[0]);
                                Double lat = parseDouble(lngLat[1]);
                                if (lng != null && lat != null) {
                                    Map<String, Double> pointMap = new HashMap<>();
                                    pointMap.put("lng", lng);
                                    pointMap.put("lat", lat);
                                    routePoints.add(pointMap);
                                }
                            }
                        }
                    }
                }
            } finally {
                response.close();
            }
        } catch (Exception e) {
            log.error("调用高德路线API失败", e);
        }
        return routePoints;
    }

    /**
     * 构建途经点字符串（高德API：;分隔经纬度）
     */
    private String buildWaypoints(List<Map<String, Object>> spots) {
        List<String> validPoints = new ArrayList<>();
        for (Map<String, Object> spot : spots) {
            Object lngObj = spot.get("lng");
            Object latObj = spot.get("lat");
            if (lngObj instanceof Double && latObj instanceof Double) {
                Double lng = (Double) lngObj;
                Double lat = (Double) latObj;
                validPoints.add(lng + "," + lat);
            }
        }
        return String.join(";", validPoints);
    }

    /**
     * 构建AI Prompt（更严谨，避免格式错误）
     */
    private String buildPrompt(String city, int days, String preference, List<Map<String, Object>> spots,
            Map<Integer, String> dayColor) {
        // 简化Prompt，避免过长；明确JSON格式要求
        return String.format("""
                你是专业的旅游行程规划师，请为%s规划%d天的行程，偏好%s。请严格按照以下规范输出：

                【重要规则】
                1. 必须为所有%d天都规划景点，不能遗漏任何一天
                2. 仅从以下景点中选择（保留name/lng/lat/address字段）：%s
                3. 每天景点按"不回头"的地理顺序排列，每天推荐3-4个景点
                4. 每生成1个景点，仅返回1段独立的JSON，格式必须为：
                   {"day": 数字, "spot": {"name": "景点名", "lng": 经度, "lat": 纬度, "address": "地址"}, "color": "颜色码"}
                5. 每天生成完所有景点后，返回1段标记完成的JSON：
                   {"day": 数字, "isDayComplete": true, "color": "颜色码"}
                6. 不同天数使用指定颜色：%s
                7. 不要返回任何多余文字、注释、换行或符号，仅返回上述JSON片段
                8. 严格按照以上规则执行，确保生成完整的行程规划
                9. 必须持续生成直到所有天数的景点都规划完毕，不能提前结束
                
                【强制约束】
                - 只能输出JSON格式，不能输出任何其他文字
                - JSON必须完整且语法正确
                - 每个JSON对象必须独立完整，不能有嵌套或截断
                - 所有字段都必须存在且类型正确
                - day字段必须是整数，从1开始连续递增
                - spot字段必须包含name/lng/lat/address四个子字段
                - lng/lat必须是浮点数格式
                - color必须是十六进制颜色码格式，如"#FF5733"
                - 每天必须有isDayComplete标记才算完成
                - 输出必须严格按照一行一个JSON对象的格式
                
                【错误示例】（禁止这样做）
                1. 不要输出解释性文字：如"第一天行程如下："等
                2. 不要输出代码块标记：如```json等
                3. 不要合并多个JSON对象：如[{"day":1...},{"day":1...}]等
                4. 不要缺少字段：每个景点JSON必须包含所有必需字段
                
                【正确示例】（严格按照此格式逐行输出）
                {"day": 1, "spot": {"name": "故宫博物院", "lng": 116.397026, "lat": 39.918058, "address": "北京市东城区景山前街4号"}, "color": "#FF5733"}
                {"day": 1, "spot": {"name": "景山公园", "lng": 116.396472, "lat": 39.924091, "address": "北京市东城区景山前街44号"}, "color": "#FF5733"}
                {"day": 1, "isDayComplete": true, "color": "#FF5733"}
                {"day": 2, "spot": {"name": "颐和园", "lng": 116.273503, "lat": 39.999612, "address": "北京市海淀区新建宫门路19号"}, "color": "#33A1FF"}
                {"day": 2, "spot": {"name": "圆明园遗址公园", "lng": 116.306348, "lat": 40.001602, "address": "北京市海淀区清华西路28号"}, "color": "#33A1FF"}
                {"day": 2, "isDayComplete": true, "color": "#33A1FF"}
                """,
                city,
                days,
                StringUtils.hasText(preference) ? preference : "综合",
                days,
                mapper.valueToTree(spots).toString(),
                mapper.valueToTree(dayColor).toString());
    }

    private String extractContent(ChatResponse resp) {
        try {
            if (resp == null || resp.getResults() == null || resp.getResults().isEmpty()) {
                return null;
            }
            
            // 修复空指针异常：检查getText()返回值是否为null
            String text = resp.getResults().get(0).getOutput().getText();
            if (text == null) {
                return null;
            }
            
            // 提取内容并过滤：去除首尾空格、换行、制表符
            String content = text.replaceAll("\\n+", "")
                    .replaceAll("\\t+", "");
            log.info("AI响应内容：{}", content);
            // 过滤空内容
            return StringUtils.hasText(content) ? content : null;
        } catch (Exception e) {
            log.error("提取AI响应内容失败", e);
            return null;
        }
    }

    /**
     * 拆分合法的JSON对象（修复嵌套{}问题）
     */
    private List<String> splitValidJson(String text) {
        List<String> validJsonList = new ArrayList<>();
        if (!StringUtils.hasText(text)) {
            return validJsonList;
        }

        int braceDepth = 0;
        int startIndex = -1;
        char[] chars = text.toCharArray();

        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c == '{') {
                if (braceDepth == 0) {
                    startIndex = i;
                }
                braceDepth++;
            } else if (c == '}') {
                braceDepth--;
                // 仅当括号闭合且是完整JSON时，才加入列表
                if (braceDepth == 0 && startIndex >= 0) {
                    String jsonStr = text.substring(startIndex, i + 1).trim();
                    // 简单校验JSON合法性
                    if (isValidJson(jsonStr)) {
                        validJsonList.add(jsonStr);
                    }
                    startIndex = -1;
                }
            }
        }
        return validJsonList;
    }

    /**
     * 简单校验JSON是否合法
     */
    private boolean isValidJson(String jsonStr) {
        if (!StringUtils.hasText(jsonStr)) {
            return false;
        }
        
        // 基本格式检查
        jsonStr = jsonStr.trim();
        if (!jsonStr.startsWith("{") || !jsonStr.endsWith("}")) {
            return false;
        }
        
        try {
            mapper.readTree(jsonStr);
            return true;
        } catch (Exception e) {
            log.warn("JSON格式不合法：{}", jsonStr);
            return false;
        }
    }

    /**
     * 处理单个AI返回的JSON片段
     */
    private void processAiJsonFragment(String jsonStr, Map<Integer, List<Map<String, Object>>> daySpots,
            Map<Integer, String> dayColor, int desiredPerDay, SseEmitter emitter) {
        try {
            // 首先验证JSON字符串的基本有效性
            if (!isValidJson(jsonStr)) {
                log.warn("AI返回无效JSON：{}", jsonStr);
                return;
            }
            
            Map<String, Object> data = mapper.readValue(jsonStr, Map.class);

            // 处理错误信息
            if (data.containsKey("error")) {
                sendError(emitter, String.valueOf(data.get("error")));
                return;
            }

            // 处理当日路线完成标记
            if (Boolean.TRUE.equals(data.get("isDayComplete"))) {
                Integer day = (Integer) data.get("day");
                if (day == null || daySpots.get(day) == null) {
                    log.warn("AI返回完成标记缺少必要字段：{}", jsonStr);
                    return;
                }
                List<Map<String, Object>> daySpotList = daySpots.get(day);
                if (!daySpotList.isEmpty()) {
                    // 生成当日路线并推送
                    List<Map<String, Double>> route = getOptimalRoute(daySpotList);
                    Map<String, Object> routePayload = new HashMap<>();
                    routePayload.put("day", day);
                    routePayload.put("color", dayColor.get(day));
                    routePayload.put("route", route);
                    routePayload.put("isDayRoute", true);
                    sendData(emitter, routePayload);
                }
                return;
            }

            // 处理普通景点数据
            Integer day = (Integer) data.get("day");
            String color = (String) data.get("color");
            Map<String, Object> spotObj = (Map<String, Object>) data.get("spot");

            // 数据验证
            if (day == null) {
                log.warn("AI返回数据缺少day字段：{}", jsonStr);
                return;
            }
            
            if (!StringUtils.hasText(color)) {
                log.warn("AI返回数据缺少color字段或color为空：{}", jsonStr);
                return;
            }
            
            if (spotObj == null) {
                log.warn("AI返回数据缺少spot字段：{}", jsonStr);
                return;
            }
            
            // 验证spot对象的必需字段
            if (!spotObj.containsKey("name") || !spotObj.containsKey("lng") || 
                !spotObj.containsKey("lat") || !spotObj.containsKey("address")) {
                log.warn("AI返回的spot对象缺少必要字段：{}", jsonStr);
                return;
            }

            // 校验/补全景点经纬度
            Map<String, Object> verifiedSpot = verifyOrCompleteSpot(spotObj,
                    daySpots.keySet().stream().findFirst().orElse(-1).toString());
            // 缓存当日景点和颜色
            dayColor.put(day, color);
            daySpots.get(day).add(verifiedSpot);

            // 推送景点数据
            Map<String, Object> payload = new HashMap<>();
            payload.put("day", day);
            payload.put("color", color);
            payload.put("spot", verifiedSpot);
            sendData(emitter, payload);

            // 当日景点数达标时，生成路线
            if (daySpots.get(day).size() >= desiredPerDay) {
                List<Map<String, Double>> route = getOptimalRoute(daySpots.get(day));
                Map<String, Object> routePayload = new HashMap<>();
                routePayload.put("day", day);
                routePayload.put("color", dayColor.get(day));
                routePayload.put("route", route);
                routePayload.put("isDayRoute", true);
                sendData(emitter, routePayload);
            }
        } catch (Exception e) {
            log.error("处理AI JSON片段失败：{}", jsonStr, e);
            sendError(emitter, "解析行程数据失败：" + e.getMessage());
        }
    }

    /**
     * 注册SSE回调，避免资源泄漏
     */
    private void registerSseCallbacks(SseEmitter emitter) {
        // 超时回调
        emitter.onTimeout(() -> {
            log.warn("SSE连接超时");
            sendError(emitter, "连接超时，请重试");
            emitter.complete();
        });

        // 完成回调
        emitter.onCompletion(() -> log.info("SSE连接正常关闭"));

        // 错误回调
        emitter.onError(ex -> {
            log.error("SSE连接异常", ex);
            emitter.completeWithError(ex);
        });
    }

    /**
     * 发送正常数据到前端
     */
    private void sendData(SseEmitter emitter, Object data) {
        try {
            emitter.send(SseEmitter.event().data(data).name("trip-data"));
        } catch (Exception e) {
            log.error("推送SSE数据失败", e);
        }
    }

    /**
     * 发送错误信息到前端
     */
    private void sendError(SseEmitter emitter, String message) {
        try {
            Map<String, Object> error = new HashMap<>();
            error.put("error", message);
            emitter.send(SseEmitter.event().data(error).name("trip-error"));
        } catch (Exception e) {
            log.error("推送SSE错误信息失败", e);
        }
    }

    /**
     * 发送完成标记
     */
    private void sendDone(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().data("[DONE]").name("trip-done"));
        } catch (Exception e) {
            log.error("推送SSE完成标记失败", e);
        }
    }

    private void sendWeather(SseEmitter emitter, Object data) {
        try {
            emitter.send(SseEmitter.event().data(data).name("trip-weather"));
        } catch (Exception e) {
            log.error("推送SSE天气信息失败", e);
        }
    }

    /**
     * 安全完成SSE连接
     */
    private void completeEmitter(SseEmitter emitter) {
        try {
            // 在完成前等待一小段时间，确保所有数据都被发送
            Thread.sleep(100);
            emitter.complete();
        } catch (Exception e) {
            log.error("关闭SSE连接失败", e);
        }
    }

    /**
     * 安全解析Double（避免空指针/格式错误）
     */
    private Double parseDouble(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NullPointerException | NumberFormatException e) {
            log.warn("解析数字失败：{}", s);
            return null;
        }
    }

    private List<Map<String, Object>> getWeatherForecast(String city, int daysLimit) {
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            URIBuilder uriBuilder = new URIBuilder("https://restapi.amap.com/v3/weather/weatherInfo");
            uriBuilder.setParameter("city", city)
                     .setParameter("key", amapKey)
                     .setParameter("extensions", "all");

            URI uri = uriBuilder.build();
            log.info("构建的天气查询URI: {}", uri.toString());

            HttpGet httpGet = new HttpGet(uri);
            CloseableHttpResponse response = httpClient.execute(httpGet);

            try {
                String responseString = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                JsonNode root = mapper.readTree(responseString);
                if (!"1".equals(root.path("status").asText())) {
                    return list;
                }
                JsonNode forecasts = root.path("forecasts");
                if (forecasts.isArray() && forecasts.size() > 0) {
                    JsonNode casts = forecasts.get(0).path("casts");
                    int count = 0;
                    for (JsonNode cast : casts) {
                        if (count >= daysLimit)
                            break;
                        String date = cast.path("date").asText("");
                        String week = cast.path("week").asText("");
                        String dayweather = cast.path("dayweather").asText("");
                        String nightweather = cast.path("nightweather").asText("");
                        String daytemp = cast.path("daytemp").asText("");
                        String nighttemp = cast.path("nighttemp").asText("");
                        Map<String, Object> item = new HashMap<>();
                        item.put("date", date);
                        item.put("weekday", toWeekday(week));
                        item.put("weather", joinWeather(dayweather, nightweather));
                        item.put("temperature", formatTemp(daytemp, nighttemp));
                        list.add(item);
                        count++;
                    }
                }
            } finally {
                response.close();
            }
        } catch (Exception e) {
            log.error("获取天气失败", e);
        }
        log.info("获取天气成功：{}", list);
        return list;
    }

    private String toWeekday(String week) {
        try {
            int w = Integer.parseInt(week);
            switch (w) {
                case 1:
                    return "周一";
                case 2:
                    return "周二";
                case 3:
                    return "周三";
                case 4:
                    return "周四";
                case 5:
                    return "周五";
                case 6:
                    return "周六";
                case 7:
                    return "周日";
                default:
                    return "未知";
            }
        } catch (Exception e) {
            return "未知";
        }
    }

    private String joinWeather(String day, String night) {
        if (StringUtils.hasText(day) && StringUtils.hasText(night))
            return day + " / " + night;
        if (StringUtils.hasText(day))
            return day;
        if (StringUtils.hasText(night))
            return night;
        return "";
    }

    private String formatTemp(String day, String night) {
        String dt = StringUtils.hasText(day) ? day + "℃" : "";
        String nt = StringUtils.hasText(night) ? night + "℃" : "";
        if (StringUtils.hasText(dt) && StringUtils.hasText(nt))
            return dt + " / " + nt;
        if (StringUtils.hasText(dt))
            return dt;
        if (StringUtils.hasText(nt))
            return nt;
        return "";
    }
}
