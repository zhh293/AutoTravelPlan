package com.zhh.handsome.gaodemcptest;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import groovy.util.logging.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.json.JsonParseException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SpringBootTest
@Slf4j

class GaoDeMcpTestApplicationTests {

    private final ObjectMapper mapper = new ObjectMapper();
    @Test
    void contextLoads() {
    }
    List<String> completeJsonList = new ArrayList<>();

    //如何解析出正确的json对象呢
    //{{},{{{},{}},{}}
    //{"day": 数字, "spot": {"name": "景点名", "lng": 经度, "lat": 纬度, "address": "地址"}, "color": "颜色码"}
    //每次一都要对StringBuilder做一次遍历，如果正好左括号等于右括号，那么就把之前的全部清空，然后只保留后面的，当然左括号等于右括号这一步最好重复，直到剩下部分不支持一整个json对象
    //下一次同理
    //最后做一次大清洗，看看有没有剩下的json对象并且把StringBuilder清空
    //优化的话可以记录上一次读取的位置，下次从上次的位置开始读取，这样避免了每次都从头开始读取
    private Map<String, Integer> extractCompleteJson(StringBuilder buf, int lastProcessedPosition, int lastLeftBracketCount, int  lastRightBracketCount) {
        Map<String, Integer> result = new HashMap<>();
        
        if (buf.length() == 0) {
            result.put("lastProcessedPosition", lastProcessedPosition);
            result.put("lastLeftBracketCount", lastLeftBracketCount);
            result.put("lastRightBracketCount", lastRightBracketCount);
            return result;
        }
        
        for(int i = lastProcessedPosition; i < buf.length(); i++){
            char c = buf.charAt(i);
            if (c == '{') {
                lastLeftBracketCount++;
            }
            if (c == '}') {
                lastRightBracketCount++;
            }
            if (lastLeftBracketCount == lastRightBracketCount && lastLeftBracketCount > 0) {
                String substring = buf.substring(0, i + 1);
                completeJsonList.add(substring);
                buf.delete(0, i + 1);
                i = -1; //重置i，因为buf变了
                lastLeftBracketCount = 0;
                lastRightBracketCount = 0;
            }
            lastProcessedPosition = i;
        }
        
        result.put("lastProcessedPosition", lastProcessedPosition);
        result.put("lastLeftBracketCount", lastLeftBracketCount);
        result.put("lastRightBracketCount", lastRightBracketCount);
        return result;
    }
    private String buildPrompt(String city, int days, String preference, List<Map<String, Object>> spots,
                               Map<Integer, String> dayColor) {
        // 简化Prompt，避免过长；明确JSON格式要求
        return String.format("""
                你需要为%s规划%d天的行程，偏好%s，严格遵守以下规则：
                1. 必须为所有%d天都规划景点，不能遗漏任何一天
                2. 仅从以下景点中选择（保留name/lng/lat/address字段）：%s
                3. 每天景点按"不回头"的地理顺序排列，每天推荐3-4个景点
                4. 每生成1个景点，仅返回1段独立的JSON，格式必须为：
                   {"day": 数字, "spot": {"name": "景点名", "lng": 经度, "lat": 纬度, "address": "地址"}, "color": "颜色码"}
                5. 每天生成完所有景点后，返回1段标记完成的JSON：
                   {"day": 数字, "isDayComplete": true, "color": "颜色码"}
                6. 不同天数使用指定颜色：%s
                7. 不要返回任何多余文字、注释、换行或符号，仅返回上述JSON片段
                8. 请严格按照以上规则执行，确保生成完整的行程规划
                9. 你必须持续生成直到所有天数的景点都规划完毕，不能提前结束
                10. 如果有任何疑问，请先确认需求再开始生成
                """,
                city,
                days,
                StringUtils.hasText(preference) ? preference : "综合",
                days,
                mapper.valueToTree(spots).toString(),
                mapper.valueToTree(dayColor).toString());
    }
    @Test
    void testModel(){

    }


    private List<String> extractCompleteJson(StringBuilder buf) {
        List<String> completeJsonList = new ArrayList<>();
        if (buf.length() == 0) {
            return completeJsonList;
        }

        try {
            // 记录初始缓冲区内容，用于调试
            String originalBuf = buf.toString();

            // 1. 构建Jackson JsonParser（支持从字符串读取）
            JsonParser jsonParser = mapper.getFactory().createParser(originalBuf);
            jsonParser.configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, false); // 严格模式
            jsonParser.configure(JsonParser.Feature.ALLOW_TRAILING_COMMA, true); // 兼容AI可能的尾随逗号

            // 2. 循环读取完整的JSON对象（可能有多个连续JSON）
            int lastProcessedPosition = 0;
            while (jsonParser.nextToken() != null) {
                if (jsonParser.getCurrentToken() == JsonToken.START_OBJECT) {
                    // 记录对象开始位置
                    int startPosition = (int) jsonParser.getTokenLocation().getCharOffset();

                    try {
                        // 尝试读取完整的JSON对象
                        JsonNode completeJsonNode = mapper.readTree(jsonParser);

                        // 只有成功读取完整对象后才添加到结果列表
                        String completeJson = mapper.writeValueAsString(completeJsonNode);
                        completeJsonList.add(completeJson);

                        // 更新最后处理位置
                        lastProcessedPosition = (int) jsonParser.getCurrentLocation().getCharOffset();
                    } catch (Exception e) {
                        // 如果解析失败，说明这是一个不完整的对象或其他问题，跳过它
                        // 尝试跳过当前对象，继续处理后续内容
                        try {
                            jsonParser.skipChildren();
                        } catch (Exception skipException) {
                            // 如果skipChildren也失败了，我们只能跳出循环
                            break;
                        }
                    }
                }
            }

            // 3. 更新缓冲区（只保留未解析的内容）
            if (lastProcessedPosition > 0 && lastProcessedPosition <= originalBuf.length()) {
                // 截取未解析的内容，覆盖原缓冲区
                String unparsed = originalBuf.substring(lastProcessedPosition).trim();
                buf.setLength(0);
                buf.append(unparsed);
            }

            jsonParser.close();
        } catch (JsonParseException e) {
            // 正常情况：缓冲区内容不完整，等待后续流式数据补充
        } catch (Exception e) {
            // 异常情况：JSON格式错误，清空缓冲区避免死循环
            buf.setLength(0);
        }

        return completeJsonList;
    }
}