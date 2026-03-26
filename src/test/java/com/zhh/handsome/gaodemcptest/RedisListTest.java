//package com.zhh.handsome.gaodemcptest;
//
//import com.zhh.handsome.gaodemcptest.async.TravelPlanTaskMessage;
//import org.junit.jupiter.api.Test;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.context.SpringBootTest;
//import org.springframework.data.redis.core.RedisTemplate;
//import org.springframework.test.context.ActiveProfiles;
//
//import java.util.UUID;
//
//@SpringBootTest
//@ActiveProfiles("test")
//public class RedisListTest {
//
//    @Autowired
//    private RedisTemplate<String, Object> redisTemplate;
//
//    private static final String QUEUE_KEY = "travel-plan:queue:test";
//
//    @Test
//    public void testRedisListOperations() {
//        // 清空测试队列
//        redisTemplate.delete(QUEUE_KEY);
//
//        // 创建测试消息
//        TravelPlanTaskMessage msg = new TravelPlanTaskMessage();
//        msg.setTaskId(UUID.randomUUID().toString());
//        msg.setLng(116.3972);
//        msg.setLat(39.9087);
//        msg.setLandmarkName("测试景点");
//        msg.setCreatedAt(System.currentTimeMillis());
//
//        System.out.println("测试消息: " + msg);
//
//        // Push消息到队列
//        Long pushResult = redisTemplate.opsForList().leftPush(QUEUE_KEY, msg);
//        System.out.println("Push结果: " + pushResult);
//
//        // 立即查询队列大小
//        Long size = redisTemplate.opsForList().size(QUEUE_KEY);
//        System.out.println("队列大小: " + size);
//
//        // 查询队列内容
//        Object peekResult = redisTemplate.opsForList().index(QUEUE_KEY, 0);
//        System.out.println("队列第一个元素: " + peekResult);
//
//        // Pop消息验证
//        Object popResult = redisTemplate.opsForList().rightPop(QUEUE_KEY);
//        System.out.println("Pop结果: " + popResult);
//        System.out.println("Pop后队列大小: " + redisTemplate.opsForList().size(QUEUE_KEY));
//
//        // 再次push测试
//        redisTemplate.opsForList().leftPush(QUEUE_KEY, msg);
//        System.out.println("再次push后队列大小: " + redisTemplate.opsForList().size(QUEUE_KEY));
//    }
//
//    @Test
//    public void testStringSerialization() {
//        // 测试使用字符串序列化
//        String testKey = "test:string:list";
//        redisTemplate.delete(testKey);
//
//        String testData = "test_data_" + System.currentTimeMillis();
//        redisTemplate.opsForList().leftPush(testKey, testData);
//
//        Long size = redisTemplate.opsForList().size(testKey);
//        Object data = redisTemplate.opsForList().index(testKey, 0);
//
//        System.out.println("字符串测试 - 大小: " + size + ", 数据: " + data);
//    }
//}