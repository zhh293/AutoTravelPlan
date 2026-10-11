////
//// Source code recreated from a .class file by IntelliJ IDEA
//// (powered by Fernflower decompiler)
////
//package com.zhh.handsome.gaodemcptest;
//
//import org.junit.jupiter.api.Test;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.ai.chat.messages.UserMessage;
//import org.springframework.ai.chat.model.ChatModel;
//import org.springframework.ai.chat.model.ChatResponse;
//import org.springframework.ai.chat.prompt.Prompt;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.context.SpringBootTest;
//
//import java.util.ArrayList;
//import java.util.HashMap;
//import java.util.List;
//import java.util.Map;
//
//@SpringBootTest
//public class SimpleTripPlanTest {
//    private static final Logger log = LoggerFactory.getLogger(SimpleTripPlanTest.class);
//
//    @Autowired
//    private ChatModel chatModel;
//
//    @Test
//    void testSimplePrompt() {
//        // 简单的测试数据
//        String city = "北京";
//        int days = 3;
//        String preference = "历史文化";
//
//        // 创建简单的prompt
//        String prompt = String.format("""
//                为%s规划%d天的行程，偏好%s。
//                请生成完整的行程规划，每天推荐3-4个景点。
//                每个景点返回一个JSON对象，包含day、spot（name、lng、lat、address）和color字段。
//                每天结束后返回一个标记完成的JSON对象。
//                不要返回任何多余文字。
//                """,
//                city,
//                days,
//                preference);
//
//        // 发送请求
//        ChatResponse response = chatModel.call(new Prompt(new UserMessage(prompt)));
//
//        // 输出结果
//        System.out.println("AI响应内容：" + response.getResults().get(0).getOutput().getText());
//
//        // 验证结果
//        String result = response.getResults().get(0).getOutput().getText();
//        assert result != null : "结果不应为空";
//
//        // 验证是否包含每天的数据
//        boolean hasDay1 = result.contains("\"day\": 1");
//        boolean hasDay2 = result.contains("\"day\": 2");
//        boolean hasDay3 = result.contains("\"day\": 3");
//
//        if (hasDay1 && hasDay2 && hasDay3) {
//            System.out.println("成功生成了3天的行程规划");
//        } else {
//            System.out.println("只生成了部分行程规划");
//        }
//
//        log.info("成功获取行程规划结果");
//    }
//}
