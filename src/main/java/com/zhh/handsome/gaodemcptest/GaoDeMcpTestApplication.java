package com.zhh.handsome.gaodemcptest;
//
//import io.modelcontextprotocol.client.McpAsyncClient;
//import io.modelcontextprotocol.spec.McpSchema;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

//import static org.springframework.ai.tool.ToolCallback.logger;


@SpringBootApplication
@RestController
@EnableDubbo
public class GaoDeMcpTestApplication {
    /*@Autowired
    List<McpAsyncClient> mcpAsyncClients;

    @RequestMapping("/test")
    public Mono<McpSchema.CallToolResult>test(){
        var mcpClient = mcpAsyncClients.get(0);
        Mono<McpSchema.CallToolResult> callToolResultMono = mcpClient.listTools()
                .flatMap(tools -> {
                    logger.info("tools: {}", tools);

                    return mcpClient.callTool(
                            new McpSchema.CallToolRequest(
                                    "maps_weather",
                                    Map.of("city", "北京")
                            )
                    );
                });
        // 2. 再调用个人地图规划工具（使用正确参数格式）
        return callToolResultMono.flatMap(weatherResult -> {
            // 构造个人地图规划的正确参数（符合inputSchema规范）
            Map<String, Object> personalMapParams = new HashMap<>();

            // 1. 行程所属的小程序名称（必填）
            personalMapParams.put("orgName", "我的旅行助手");

            // 2. 行程列表（必填）
            List<Map<String, Object>> lineList = new ArrayList<>();

            // 2.1 第一个行程段落
            Map<String, Object> firstLine = new HashMap<>();
            firstLine.put("title", "湖南一日游"); // 行程段落名称

            // 2.2 行程点列表（按顺序排列）
            List<Map<String, Object>> pointInfoList = new ArrayList<>();

            // 起点：五一广场
            Map<String, Object> startPoint = new HashMap<>();
            startPoint.put("name", "新大新五一广场");
            startPoint.put("lon", 112.985478); // 经度（示例值，需替换为实际坐标）
            startPoint.put("lat", 28.201917);  // 纬度（示例值，需替换为实际坐标）
            startPoint.put("poiId", "B0KUPDVHHS"); // POI唯一标识（示例值，需替换为实际值）
            pointInfoList.add(startPoint);

            // 途经点：青岛海滨风景区
            Map<String, Object> wayPoint = new HashMap<>();
            wayPoint.put("name", "青岛海滨风景区");
            wayPoint.put("lon", 112.977340); // 经度（示例值）
            wayPoint.put("lat", 28.196500);  // 纬度（示例值）
            wayPoint.put("poiId", "B02DB066DT"); // POI唯一标识（示例值）
            pointInfoList.add(wayPoint);

            // 终点：五一广场（回到起点）
            Map<String, Object> endPoint = new HashMap<>();
            endPoint.put("name", "五一广场地铁站城市值机区");
            endPoint.put("lon", 112.976078); // 与起点相同
            endPoint.put("lat", 28.195007);
            endPoint.put("poiId", "B0JK4AQAO5");
            pointInfoList.add(endPoint);

            // 将行程点列表放入行程段落
            firstLine.put("pointInfoList", pointInfoList);

            // 将行程段落放入行程列表
            lineList.add(firstLine);

            // 将行程列表放入参数
            personalMapParams.put("lineList", lineList);

            // 调用工具
            return mcpClient.callTool(
                    new McpSchema.CallToolRequest(
                            "maps_schema_personal_map",
                            personalMapParams
                    )
            ).doOnNext(mapResult -> {
                logger.info("个人地图生成结果: {}", mapResult);
            }).onErrorResume(e -> {
                logger.error("工具调用失败", e);
                return Mono.empty();
            });
        });

    }
*/

    public static void main(String[] args) {
        SpringApplication.run(GaoDeMcpTestApplication.class, args);
    }

}
