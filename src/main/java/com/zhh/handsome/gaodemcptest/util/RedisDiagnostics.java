package com.zhh.handsome.gaodemcptest.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis诊断工具类
 * 用于检查Redis连接和序列化配置
 */
@Slf4j
@Component
public class RedisDiagnostics {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * 诊断Redis配置和连接状态
     */
    public void diagnoseRedisConfiguration() {
        try {
            log.info("=== Redis配置诊断开始 ===");
            
            // 1. 测试基本连接
            String testKey = "diagnostic:test:" + System.currentTimeMillis();
            String testValue = "connection_test";
            
            redisTemplate.opsForValue().set(testKey, testValue);
            Object retrievedValue = redisTemplate.opsForValue().get(testKey);
            
            log.info("连接测试 - 写入: {}, 读取: {}, 成功: {}", 
                    testValue, retrievedValue, testValue.equals(retrievedValue));
            
            // 2. 测试List操作
            String listKey = "diagnostic:list:" + System.currentTimeMillis();
            String listItem = "list_item_test";
            
            Long pushResult = redisTemplate.opsForList().leftPush(listKey, listItem);
            Long listSize = redisTemplate.opsForList().size(listKey);
            Object listFirst = redisTemplate.opsForList().index(listKey, 0);
            
            log.info("List操作测试 - Push结果: {}, 大小: {}, 第一个元素: {}", 
                    pushResult, listSize, listFirst);
            
            // 3. 检查序列化配置
            log.info("Key序列化器: {}", redisTemplate.getKeySerializer().getClass().getSimpleName());
            log.info("Value序列化器: {}", redisTemplate.getValueSerializer().getClass().getSimpleName());
            log.info("HashKey序列化器: {}", redisTemplate.getHashKeySerializer().getClass().getSimpleName());
            log.info("HashValue序列化器: {}", redisTemplate.getHashValueSerializer().getClass().getSimpleName());
            
            // 4. 清理测试数据
            redisTemplate.delete(testKey);
            redisTemplate.delete(listKey);
            
            log.info("=== Redis配置诊断完成 ===");
            
        } catch (Exception e) {
            log.error("Redis诊断过程中发生错误", e);
        }
    }

    /**
     * 测试特定队列的状态
     */
    public void diagnoseQueue(String queueKey) {
        try {
            log.info("=== 队列诊断: {} ===", queueKey);
            
            Long size = redisTemplate.opsForList().size(queueKey);
            log.info("队列大小: {}", size);
            
            if (size != null && size > 0) {
                Object firstElement = redisTemplate.opsForList().index(queueKey, 0);
                Object lastElement = redisTemplate.opsForList().index(queueKey, size - 1);
                
                log.info("第一个元素: {}", firstElement);
                log.info("最后一个元素: {}", lastElement);
                
                // 尝试弹出一个元素测试
                Object popped = redisTemplate.opsForList().rightPop(queueKey);
                log.info("弹出元素: {}", popped);
                
                // 重新放回去
                if (popped != null) {
                    redisTemplate.opsForList().leftPush(queueKey, popped);
                }
            } else {
                log.info("队列为空");
            }
            
            log.info("=== 队列诊断完成 ===");
            
        } catch (Exception e) {
            log.error("队列诊断过程中发生错误", e);
        }
    }
}