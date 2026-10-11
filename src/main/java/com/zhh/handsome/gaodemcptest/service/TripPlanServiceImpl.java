package com.zhh.handsome.gaodemcptest.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhh.handsome.gaodemcptest.model.AmapPoiUtils;
import com.zhh.handsome.gaodemcptest.model.TripPlanItem;
import jakarta.annotation.PreDestroy;
import org.apache.http.client.config.RequestConfig;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
//import org.apache.dubbo.config.annotation.DubboService;
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
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.core.task.TaskExecutor;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;

@Service
// @DubboService
public class TripPlanServiceImpl implements TripPlanService {
    private static final Logger log = LoggerFactory.getLogger(TripPlanServiceImpl.class);

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

    private final AmapPoiUtils amapPoiUtils;

    private final CloseableHttpClient httpClient = HttpClients.custom().setDefaultRequestConfig(RequestConfig.custom()
            .setConnectTimeout(5000).setConnectionRequestTimeout(5000).setSocketTimeout(10000).build()).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ChatModel chatModel;
    private final TaskExecutor taskExecutor;
    private final TripPlanStreamableSseStore streamableSseStore;
    private final Map<String, AtomicBoolean> runningTasks = new ConcurrentHashMap<>();
    private final Map<SseEmitter, String> emitterTaskMap = new ConcurrentHashMap<>();
    private final Map<SseEmitter, AtomicBoolean> connectionStops = new ConcurrentHashMap<>();
    private final ThreadPoolTaskExecutor sseReaderExecutor = new ThreadPoolTaskExecutor();

    @Value("${trip.plan.max-duration-seconds:120}")
    private long maxDurationSeconds = 120;
    @Value("${trip.plan.ai-idle-timeout-seconds:45}")
    private long aiIdleTimeoutSeconds = 45;

    public TripPlanServiceImpl(
            AmapPoiUtils amapPoiUtils,
            ChatModel chatModel,
            TaskExecutor tripTaskExecutor,
            TripPlanStreamableSseStore streamableSseStore) {
        this.amapPoiUtils = amapPoiUtils;
        this.chatModel = chatModel;
        this.taskExecutor = tripTaskExecutor;
        this.streamableSseStore = streamableSseStore;
        sseReaderExecutor.setCorePoolSize(8);
        sseReaderExecutor.setMaxPoolSize(8);
        sseReaderExecutor.setQueueCapacity(32);
        sseReaderExecutor.setThreadNamePrefix("trip-sse-");
        sseReaderExecutor.initialize();
    }

    public SseEmitter streamPlan(TripPlanItem tripPlanItem, int days, String preference, String taskId,
            String lastEventId) {
        if (tripPlanItem == null || !StringUtils.hasText(tripPlanItem.getCity()) || days < 1 || days > 30
                || tripPlanItem.getLng() == null || tripPlanItem.getLat() == null
                || !Double.isFinite(tripPlanItem.getLng()) || !Double.isFinite(tripPlanItem.getLat())
                || Math.abs(tripPlanItem.getLng()) > 180 || Math.abs(tripPlanItem.getLat()) > 90) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "城市、经纬度和行程天数（1～30）必须有效");
        }
        String city = tripPlanItem.getCity();
        String currentTaskId = StringUtils.hasText(taskId) ? taskId : UUID.randomUUID().toString();
        SseEmitter connection = new SseEmitter(SSE_TIMEOUT);
        AtomicBoolean stop = new AtomicBoolean(false);
        connectionStops.put(connection, stop);
        registerSseCallbacks(connection);
        try {
            // 独立线程池读取 Redis；生成端只写入事件，不直接发送到连接。
            sseReaderExecutor.execute(() -> resumeHistoryAndTailRealtime(connection, currentTaskId, lastEventId));
            boolean existing = StringUtils.hasText(taskId) && streamableSseStore.hasAnyEvent(currentTaskId);
            AtomicBoolean reservation = new AtomicBoolean(true);
            if (!existing && runningTasks.putIfAbsent(currentTaskId, reservation) == null) {
                try {
                    long deadline = System.nanoTime() + Duration.ofSeconds(maxDurationSeconds).toNanos();
                    taskExecutor.execute(() -> generatePlan(tripPlanItem, days, preference, currentTaskId,
                            reservation, deadline));
                } catch (RuntimeException ex) {
                    runningTasks.remove(currentTaskId, reservation);
                    throw ex;
                }
            }
        } catch (RuntimeException ex) {
            closeConnection(connection);
            connection.completeWithError(ex);
            if (ex instanceof java.util.concurrent.RejectedExecutionException) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "行程服务繁忙，请稍后重试", ex);
            }
            throw ex;
        }
        return connection;
    }

    private void generatePlan(TripPlanItem tripPlanItem, int days, String preference, String currentTaskId,
            AtomicBoolean reservation, long deadline) {
        String city = tripPlanItem.getCity();
        // 该 emitter 仅用作现有事件写入方法的任务上下文，不绑定 HTTP 连接。
        SseEmitter emitter = new SseEmitter();
        emitterTaskMap.put(emitter, currentTaskId);
        try {
            checkDeadline(deadline);
            log.info("开始生成行程, taskId={}, city={}, days={}", currentTaskId, city, days);
            sendTaskMeta(emitter, currentTaskId);
            // 获取城市景点
            // List<Map<String, Object>> spots = getCityScenicSpots(city);
            List<Map<String, Object>> spots = amapPoiUtils.getScenicSpotsAround(tripPlanItem.getLng(),
                    tripPlanItem.getLat());
            if (spots.isEmpty()) {
                sendError(emitter, "未获取到[" + city + "]的景点数据");
                return;
            }

            // 天气
            List<Map<String, Object>> weather = getWeatherForecast(city, Math.min(2, days));
            log.info("获取天气数据：{}", weather);
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
            String prompt = buildPrompt(tripPlanItem, days, preference, spots, dayColor);
            log.info("构建Prompt：{}", prompt);
            StringBuilder buf = new StringBuilder();
            List<String> completeJsonList = new ArrayList<>();
            Map<String, Integer> state = new HashMap<>();
            state.put("lastProcessedPosition", 0);
            state.put("lastLeftBracketCount", 0);
            state.put("lastRightBracketCount", 0);
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new TimeoutException("行程生成超时");
            }
            // 关闭 Stream 会取消订阅。JSON 解析和高德调用在工作线程执行。
            try (Stream<ChatResponse> responses = chatModel.stream(new Prompt(new UserMessage(prompt)))
                    .timeout(Duration.ofSeconds(aiIdleTimeoutSeconds))
                    .takeUntilOther(Mono.delay(Duration.ofNanos(remaining))
                            .flatMap(tick -> Mono.error(new TimeoutException("行程生成超时"))))
                    .toStream(1)) {
                java.util.Iterator<ChatResponse> iterator = responses.iterator();
                while (iterator.hasNext()) {
                    checkDeadline(deadline);
                    String content = extractContent(iterator.next());
                    if (StringUtils.hasText(content)) {
                        if (buf.length() + content.length() > 1_048_576) {
                            throw new IllegalStateException("AI未完成的JSON内容超过限制");
                        }
                        buf.append(content);
                        processBufferedJson(buf, completeJsonList, state, daySpots, dayColor, desiredPerDay, emitter, deadline);
                    }
                }
                checkDeadline(deadline);
                processBufferedJson(buf, completeJsonList, state, daySpots, dayColor, desiredPerDay, emitter, deadline);
                checkDeadline(deadline);
                sendDone(emitter);
            }
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("行程生成失败, taskId={}", currentTaskId, ex);
            try {
                sendError(emitter, "行程生成失败，请重试");
            } catch (RuntimeException writeError) {
                log.error("保存失败事件失败, taskId={}", currentTaskId, writeError);
            }
        } finally {
            try {
                streamableSseStore.markFinished(currentTaskId);
            } catch (RuntimeException ex) {
                log.error("保存任务结束状态失败, taskId={}", currentTaskId, ex);
            } finally {
                emitterTaskMap.remove(emitter);
                runningTasks.remove(currentTaskId, reservation);
            }
        }
    }

    private void checkDeadline(long deadline) throws InterruptedException, TimeoutException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("行程生成被中断");
        }
        if (System.nanoTime() >= deadline) {
            throw new TimeoutException("行程生成超时");
        }
    }

    private void processBufferedJson(StringBuilder buf, List<String> jsons, Map<String, Integer> state,
            Map<Integer, List<Map<String, Object>>> daySpots, Map<Integer, String> dayColor,
            int desiredPerDay, SseEmitter emitter, long deadline) throws InterruptedException, TimeoutException {
        Map<String, Integer> next = extractCompleteJson(buf, jsons, state.get("lastProcessedPosition"),
                state.get("lastLeftBracketCount"), state.get("lastRightBracketCount"));
        state.clear();
        state.putAll(next);
        for (String json : jsons) {
            checkDeadline(deadline);
            processAiJsonFragment(json, daySpots, dayColor, desiredPerDay, emitter);
        }
        jsons.clear();
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
                        // 拿到photos的json数组
                        JsonNode photos = poi.path("photos");
                        String photoUrl = !photos.isEmpty() ? photos.get(0).path("url").asText("") : "";
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
                        spot.put("url", photoUrl);
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
     * 
     * @param buf                   字符串缓冲区
     * @param completeJsonList      用于存储解析到的完整JSON对象的列表
     * @param lastProcessedPosition 上次处理的位置
     * @param lastLeftBracketCount  上次处理的左括号计数
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
    private String buildPrompt(TripPlanItem tripPlanItem, int days, String preference, List<Map<String, Object>> spots,
            Map<Integer, String> dayColor) {
        String city = tripPlanItem.getCity();
        String title = tripPlanItem.getTitle();
        // 简化Prompt，避免过长；明确JSON格式要求
        return String.format(
                """
                        你是专业的旅游行程规划师，请以%s城市的%s景点为中心(若中心景点名字与城市名字一样的话则忽略景点，若景点名字和城市名字不一样的话则下文规划中一定要出现景点的名字，景点的经纬度分别是%s和%s)规划%d天的行程，偏好%s。请严格按照以下规范输出：
                        中心景点与城市名不一样则一定要出现在某一天的规划当中！！！！！！！！！！！！
                        【重要规则】
                        1. 必须为所有%d天都规划景点，不能遗漏任何一天
                        2. 仅从以下景点中选择（保留name/lng/lat/address/url字段）：%s和中心景点，即%s,中心景点与城市名不一样则一定要出现在某一天的规划当中！！！！！！！！！！！！
                        3. 每天景点按"不回头"的地理顺序排列，每天推荐3-4个景点
                        4. 每生成1个景点，仅返回1段独立的JSON，格式必须为：
                           {"day": 数字, "spot": {"name": "景点名", "lng": 经度, "lat": 纬度, "address": "地址","url":"https://example.com"}, "color": "颜色码"}
                        5. 每天生成完所有景点后，返回1段标记完成的JSON：
                           {"day": 数字, "isDayComplete": true, "color": "颜色码"}
                        6. 不同天数使用指定颜色：%s
                        7. 不要返回任何多余文字、注释、换行或符号，仅返回上述JSON片段
                        8. 严格按照以上规则执行，确保生成完整的行程规划
                        9. 必须持续生成直到所有天数的景点都规划完毕，不能提前结束
                        10.中心景点与城市名不一样则一定要出现在某一天的规划当中！！！！！！！！！！！！

                        【强制约束】
                        - 只能输出JSON格式，不能输出任何其他文字
                        - JSON必须完整且语法正确
                        - 每个JSON对象必须独立完整，不能有嵌套或截断
                        - 所有字段都必须存在且类型正确
                        - day字段必须是整数，从1开始连续递增
                        - spot字段必须包含name/lng/lat/address/url五个子字段
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
                        {"day": 1, "spot": {"name": "故宫博物院", "lng": 116.397026, "lat": 39.918058, "address": "北京市东城区景山前街4号","url":"https://example.com"}, "color": "#FF5733"}
                        {"day": 1, "spot": {"name": "景山公园", "lng": 116.396472, "lat": 39.924091, "address": "北京市东城区景山前街44号","url":"https://example.com"}, "color": "#FF5733"}
                        {"day": 1, "isDayComplete": true, "color": "#FF5733"}
                        {"day": 2, "spot": {"name": "颐和园", "lng": 116.273503, "lat": 39.999612, "address": "北京市海淀区新建宫门路19号","url":"https://example.com"}, "color": "#33A1FF"}
                        {"day": 2, "spot": {"name": "圆明园遗址公园", "lng": 116.306348, "lat": 40.001602, "address": "北京市海淀区清华西路28号","url":"https://example.com"}, "color": "#33A1FF"}
                        {"day": 2, "isDayComplete": true, "color": "#33A1FF"}
                        """,
                city,
                title,
                tripPlanItem.getLat(),
                tripPlanItem.getLng(),
                days,
                StringUtils.hasText(preference) ? preference : "综合",
                days,
                mapper.valueToTree(spots).toString(),
                title,
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
                throw new IllegalStateException("AI返回错误：" + data.get("error"));
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
            log.info("推送景点数据：{}", payload);
            sendData(emitter, payload);

            // 当日景点数达标时，生成路线
            if (daySpots.get(day).size() >= desiredPerDay) {
                List<Map<String, Double>> route = getOptimalRoute(daySpots.get(day));
                Map<String, Object> routePayload = new HashMap<>();
                routePayload.put("day", day);
                routePayload.put("color", dayColor.get(day));
                routePayload.put("route", route);
                routePayload.put("isDayRoute", true);
                log.info("推送路线数据：{}", routePayload);
                sendData(emitter, routePayload);
            }
        } catch (Exception e) {
            log.error("处理AI JSON片段失败：{}", jsonStr, e);
            throw new IllegalStateException("解析行程数据失败", e);
        }
    }

    /**
     * 注册SSE回调，避免资源泄漏
     */
    private void registerSseCallbacks(SseEmitter emitter) {
        emitter.onTimeout(() -> {
            closeConnection(emitter);
            emitter.complete();
        });
        emitter.onCompletion(() -> closeConnection(emitter));
        emitter.onError(ex -> closeConnection(emitter));
    }

    private void closeConnection(SseEmitter emitter) {
        AtomicBoolean stop = connectionStops.remove(emitter);
        if (stop != null) {
            stop.set(true);
        }
    }

    private void resumeHistoryAndTailRealtime(SseEmitter emitter, String taskId, String lastEventId) {
        AtomicBoolean stop = connectionStops.get(emitter);
        if (stop == null || stop.get()) {
            return;
        }
        try {
            // 从指定游标持续读取同时覆盖历史与实时数据，避免历史批次丢失。
            streamableSseStore.streamRealtimeUntilDone(taskId, lastEventId, emitter, stop);
            if (!stop.get()) {
                emitter.complete();
            }
        } catch (Exception ex) {
            if (!stop.get()) {
                log.error("读取SSE事件失败, taskId={}", taskId, ex);
                emitter.completeWithError(ex);
            }
        } finally {
            closeConnection(emitter);
        }
    }

    /**
     * 发送正常数据到前端
     */
    private void sendData(SseEmitter emitter, Object data) {
        appendForEmitter(emitter, "trip-data", data);
    }

    private void sendError(SseEmitter emitter, String message) {
        Map<String, Object> error = new HashMap<>();
        error.put("error", message);
        appendForEmitter(emitter, "trip-error", error);
    }

    private void sendDone(SseEmitter emitter) {
        appendForEmitter(emitter, "trip-done", "[DONE]");
    }

    private void sendWeather(SseEmitter emitter, Object data) {
        appendForEmitter(emitter, "trip-weather", data);
    }

    private void sendTaskMeta(SseEmitter emitter, String taskId) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("taskId", taskId);
        appendForEmitter(emitter, "trip-meta", meta);
    }

    private String appendForEmitter(SseEmitter emitter, String eventName, Object payload) {
        String taskId = emitterTaskMap.get(emitter);
        if (!StringUtils.hasText(taskId)) {
            throw new IllegalStateException("任务上下文已释放");
        }
        return streamableSseStore.appendEvent(taskId, eventName, payload);
    }

    @PreDestroy
    public void shutdown() throws java.io.IOException {
        connectionStops.values().forEach(stop -> stop.set(true));
        connectionStops.clear();
        sseReaderExecutor.shutdown();
        httpClient.close();
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
