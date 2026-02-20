package com.zhh.handsome.gaodemcptest.model;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

public class LocationCouncilPrompt {

        public static String build(double lat,
                        double lng,
                        String poiListText,
                        String sceneTagsText,
                        String rawImageDescription) {
                String poiBlock = poiListText == null || poiListText.trim().isEmpty()
                                ? "暂无可用 POI 信息（如果你无法做出可靠判断，请在输出中显式说明，且不要虚构景点）。"
                                : poiListText.trim();

                String now = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

                StringBuilder sb = new StringBuilder();
                sb.append("你是一组多模型协同系统（LLM Council）中的一员，本轮任务聚焦于：")
                                .append("【根据给定的经纬度和周边景点列表，筛选最适合旅游打卡的 5 个景点，并为用户生成结构化的出游推荐 JSON 数据】。")
                                .append("\n\n")
                                .append("【一、输入数据总览】\n")
                                .append("1. 精准经纬度（照片所在区域的参考坐标）：\n")
                                .append("   - latitude = ").append(lat).append("\n")
                                .append("   - longitude = ").append(lng).append("\n\n")
                                .append("2. 该经纬度附近通过地图服务获取的「周边景点/设施 POI 列表」（已按距离做过初步过滤和排序，越靠前通常越近）：\n")
                                .append(poiBlock).append("\n\n")
                                .append("【二、本轮多 AI 协同任务说明】\n")
                                .append("1. 你当前扮演的是多模型委员会中的一员，需要在给定 POI 列表中，挑选出【最适合旅游和拍照打卡】的 5 个景点。\n")
                                .append("2. 后续系统会收集多个模型的候选结果，进行匿名化投票和加权汇总，最终由主席模型生成对用户可见的推荐结果。\n")
                                .append("3. 因此，你必须输出结构清晰、字段稳定、便于机器解析的 JSON，而不是普通散文。\n\n")
                                .append("【三、选择景点时的判断原则】\n")
                                .append("1. 优先考虑适合【旅游】【观光】【拍照打卡】的 POI，例如景区、观景台、特色街区、公园、广场、地标建筑等。\n")
                                .append("2. 若 POI 中包含明显的非旅游类点位（如银行、公司、医院、普通小区等），一般不应作为主要推荐目标，除非存在特别合理的场景解释。\n")
                                .append("3. 在 5 个景点中，尽量保证体验的多样性（例如：视野开阔的观景点 + 具有特色氛围的街区 + 适合夜景/日落的机位等），而不是 5 个高度雷同的地点。\n")
                                .append("4. 如果 POI 信息明显不足以支撑可靠判断，你应当降低置信度，并在推荐理由中明确说明不确定性来源。\n\n")
                                .append("【四、关于“文艺青年”风格的文案要求（极其重要）】\n")
                                .append("在下面的 JSON 结构中，特别是每个景点的 \"caption\" 字段，你需要写出一种典型“文艺青年”的旅行笔记风格，要求如下：\n")
                                .append("1. 文字应当有画面感：多描写光影、天气、街道气味、人群氛围、脚步声、远处的灯光或山脊线等细节，而不是机械罗列信息。\n")
                                .append("2. 文案要有情绪：可以带一点轻微的感伤、期待、松弛感或生活感悟，让人读完会想“我也想站在这里看看”。\n")
                                .append("3. 适度比喻和拟人：可以把风、河、水面、建筑当作有情绪的角色，但不要过度浮夸，要在克制中带一点小浪漫。\n")
                                .append("4. 必须点出这个景点本身的独特之处：例如“转角的旧招牌”“黄昏时刚亮起的路灯”“桥下被风吹皱的河面”“山顶吹散汗水的风”等，让人感觉这是这里而不是任何一个泛泛的地方。\n")
                                .append("5. 文案长度不能太短，一般不少于 80 个汉字，可以在 80~300 字之间自然展开，但不要无意义地堆砌形容词，要有起承转合（比如：抵达—感受—细节—总结）。\n")
                                .append("6. 整体语气可以参考小红书/微博上一些走心的旅行随笔：第一人称或第二人称都可以，但要真诚、自然，少用网络流行语，避免机械的“打卡”“必去”堆砌。\n\n")
                                .append("【五、你需要输出的 JSON 结构（必须严格遵守）】\n")
                                .append("你最终只能输出一个 JSON 对象，不能输出任何额外的文字说明。JSON 结构必须为：\n\n")
                                .append("{\n")
                                .append("  \"latitude\": <double, 与输入纬度相同>,\n")
                                .append("  \"longitude\": <double, 与输入经度相同>,\n")
                                .append("  \"items\": [\n")
                                .append("    {\n")
                                .append("      \"poiName\": \"景点名称，必须来自给定 POI 列表\",\n")
                                .append("      \"address\": \"景点地址或大致位置描述，尽量使用 POI 中的地址信息\",\n")
                                .append("      \"scene\": \"场景类型标签，例如：城市夜景、江边步道、老街巷子、山顶观景台等\",\n")
                                .append("      \"bestVisitTime\": \"推荐前往的时间段/季节，例如：秋季日落前 1 小时、工作日白天等\",\n")
                                .append("      \"recommendation\": \"简要推荐理由，说明为什么适合旅游和拍照打卡\",\n")
                                .append("      \"caption\": \"一段适合发在社交平台的中文短文案，语气自然、有画面感\",\n")
                                                                .append("      \"url\": \"景点图片URL地址，用于展示景点照片\"\n")
                                .append("    ")
                                .append("    }\n")
                                .append("  ]\n")
                                .append("}\n\n")
                                .append("严格要求：\n")
                                .append("1. \"items\" 数组中必须包含【恰好 5 个】元素，不能多也不能少；如果你认为 POI 信息不足以支撑 5 个高质量推荐，可以在某些元素的 \"recommendation\" 中标注不确定性，但仍需给出 5 个候选。\n")
                                .append("2. 所有字段名必须完全按照上面的拼写，不要新增或删除字段，不要更改字段名。\n")
                                .append("3. JSON 外层不能出现任何额外的文字、注释或解释，不能使用 Markdown 代码块标记，只能返回纯 JSON。\n")
                                .append("4. 字符串内容请使用简体中文，数值字段使用标准数字格式。\n\n")
                                .append("【六、输出风格与边界】\n")
                                .append("1. 不要虚构明显不存在的地标名称，如果你对具体地名没有把握，请优先使用 POI 中给出的名称。\n")
                                .append("2. 不要输出任何涉及违法、违规、危险攀爬、翻越护栏等内容的建议。\n")
                                .append("3. 文案风格可以有一点社交媒体色彩，但不要过度夸张，保持真实可靠。\n\n")
                                .append("【最终任务】\n")
                                .append("基于以上经纬度与周边 POI 列表，请直接输出上述格式的 JSON，对该区域最适合旅游和拍照打卡的 5 个景点给出结构化推荐结果。")
                                .append("\n")
                                .append("当前任务时间戳: ").append(now);

                return sb.toString();
        }
}
