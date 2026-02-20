package com.zhh.handsome.gaodemcptest.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class LocationLlmCouncil {

    @Value("${llm.council.api-key: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}")
    private String apiKey;

    @Value("${llm.council.api-url:https://openrouter.ai/api/v1/chat/completions}")
    private String apiUrl;

    @Value("${llm.council.models:deepseek/deepseek-r1-0528,deepseek/deepseek-r1-0528,deepseek/deepseek-r1-0528,deepseek/deepseek-r1-0528}")
    private String councilModelsConfig;

    @Value("${llm.council.chairman-model:deepseek/deepseek-r1-0528}")
    private String chairmanModel;

    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Executor llmCouncilExecutor;

    public LocationLlmCouncil(@Qualifier("llmCouncilExecutor") Executor llmCouncilExecutor) {
        this.webClient = WebClient.builder().build();
        this.llmCouncilExecutor = llmCouncilExecutor;
    }

    public String runFullCouncil(String userQuery) {
        if (apiKey == null || apiKey.isEmpty()) {
            return "LLM Council 未配置 API Key（llm.council.api-key），无法调用多模型讨论。";
        }

        List<String> models = parseModels();
        if (models.isEmpty()) {
            return "LLM Council 未配置任何模型（llm.council.models）。";
        }

        try {
            List<ModelResponse> stage1Results = stage1CollectResponses(userQuery, models);
            if (stage1Results.isEmpty()) {
                return "Stage 1 无任何模型返回结果，多 AI 讨论无法进行。";
            }
            log.info("Stage 1 模型返回结果: {}", stage1Results);

            Stage2Result stage2 = stage2CollectRankings(userQuery, stage1Results, models);

            log.info("Stage 2 模型返回结果: {}", stage2.stage2Results);
            String finalResponse = stage3SynthesizeFinal(
                    userQuery,
                    stage1Results,
                    stage2.stage2Results,
                    stage2.labelToModel);

            log.info("Stage 3 模型返回结果: {}", finalResponse);
            return finalResponse != null && !finalResponse.isEmpty()
                    ? finalResponse
                    : "主模型未能给出最终汇总结论。";

        } catch (Exception e) {
            log.error("运行多 AI 讨论流程时发生错误", e);
            return "运行多 AI 讨论流程时发生错误: " + e.getMessage();
        }
    }

    private List<String> parseModels() {
        if (councilModelsConfig == null || councilModelsConfig.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String[] arr = councilModelsConfig.split(",");
        List<String> models = new ArrayList<>();
        for (String s : arr) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) {
                models.add(trimmed);
            }
        }
        return models;
    }

    private List<ModelResponse> stage1CollectResponses(String userQuery, List<String> models) {
        List<CompletableFuture<ModelResponse>> futures = new ArrayList<>();
        for (String model : models) {
            futures.add(queryModel(model, userQuery));
        }
        CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        all.join();

        log.info("Stage 1 模型返回结果: ");

        List<ModelResponse> results = new ArrayList<>();
        for (CompletableFuture<ModelResponse> f : futures) {
            try {
                ModelResponse r = f.get();
                log.info("Stage 1 模型返回的所有的结果: {}", r);
                if (r != null && r.content != null && !r.content.isEmpty()) {
                    results.add(r);
                }
            } catch (Exception e) {
                log.error("Error processing model response: {}", e.getMessage());
            }
        }
        return results;
    }

    private Stage2Result stage2CollectRankings(String userQuery,
            List<ModelResponse> stage1Results,
            List<String> models) {
        if (stage1Results.isEmpty()) {
            return new Stage2Result(Collections.emptyList(), Collections.emptyMap());
        }

        List<String> labels = new ArrayList<>();
        for (int i = 0; i < stage1Results.size(); i++) {
            labels.add(String.valueOf((char) ('A' + i)));
        }

        Map<String, String> labelToModel = new HashMap<>();
        for (int i = 0; i < labels.size(); i++) {
            labelToModel.put("Response " + labels.get(i), stage1Results.get(i).model);
        }

        StringBuilder responsesText = new StringBuilder();
        for (int i = 0; i < labels.size(); i++) {
            responsesText.append("Response ")
                    .append(labels.get(i))
                    .append(":\n")
                    .append(stage1Results.get(i).content)
                    .append("\n\n");
        }

        String rankingPrompt = buildRankingPrompt(userQuery, responsesText.toString());

        List<CompletableFuture<ModelRanking>> futures = new ArrayList<>();
        for (String model : models) {
            futures.add(queryModel(model, rankingPrompt)
                    .thenApply(resp -> {
                        if (resp == null || resp.content == null) {
                            return null;
                        }
                        List<String> parsed = parseRankingFromText(resp.content);
                        ModelRanking ranking = new ModelRanking();
                        ranking.model = resp.model;
                        ranking.rankingResponse = resp.content;
                        ranking.parsedRanking = parsed;
                        return ranking;
                    }));
        }

        CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        all.join();

        List<ModelRanking> stage2Results = new ArrayList<>();
        for (CompletableFuture<ModelRanking> f : futures) {
            try {
                ModelRanking r = f.get();
                if (r != null) {
                    stage2Results.add(r);
                }
            } catch (Exception ignored) {
            }
        }

        return new Stage2Result(stage2Results, labelToModel);
    }

    private String stage3SynthesizeFinal(String userQuery,
            List<ModelResponse> stage1Results,
            List<ModelRanking> stage2Results,
            Map<String, String> labelToModel) {
        if (stage1Results.isEmpty()) {
            return "Stage 1 无有效结果，无法汇总。";
        }

        StringBuilder stage1Text = new StringBuilder();
        for (ModelResponse r : stage1Results) {
            stage1Text.append("Model: ")
                    .append(r.model)
                    .append("\nResponse:\n")
                    .append(r.content)
                    .append("\n\n");
        }

        StringBuilder stage2Text = new StringBuilder();
        for (ModelRanking r : stage2Results) {
            stage2Text.append("Model: ")
                    .append(r.model)
                    .append("\nRanking:\n")
                    .append(r.rankingResponse)
                    .append("\n\n");
        }

        String chairmanPrompt = buildChairmanPrompt(userQuery, stage1Text.toString(), stage2Text.toString());

        try {
            ModelResponse resp = queryModel(chairmanModel, chairmanPrompt).get();
            if (resp != null && resp.content != null && !resp.content.isEmpty()) {
                return resp.content;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String buildRankingPrompt(String userQuery, String responsesText) {
        return "You are evaluating different responses to the following question:\n\n"
                + "Question: " + userQuery + "\n\n"
                + "Here are the responses from different models (anonymized):\n\n"
                + responsesText + "\n"
                + "Your task:\n"
                + "1. First, evaluate each response individually. For each response, explain what it does well and what it does poorly.\n"
                + "2. Then, at the very end of your response, provide a final ranking.\n\n"
                + "IMPORTANT: Your final ranking MUST be formatted EXACTLY as follows:\n"
                + "- Start with the line \"FINAL RANKING:\" (all caps, with colon)\n"
                + "- Then list the responses from best to worst as a numbered list\n"
                + "- Each line should be: number, period, space, then ONLY the response label (e.g., \"1. Response A\")\n"
                + "- Do not add any other text or explanations in the ranking section\n\n"
                + "Example of the correct format for your ENTIRE response:\n\n"
                + "Response A provides good detail on X but misses Y...\n"
                + "Response B is accurate but lacks depth on Z...\n"
                + "Response C offers the most comprehensive answer...\n\n"
                + "FINAL RANKING:\n"
                + "1. Response C\n"
                + "2. Response A\n"
                + "3. Response B\n\n"
                + "Now provide your evaluation and ranking:";
    }

    private String buildChairmanPrompt(String userQuery,
            String stage1Text,
            String stage2Text) {
        return "You are the Chairman of an LLM Council. Multiple AI models have provided responses to a user's question, "
                + "and then ranked each other's responses.\n\n"
                + "Original Question (includes a strict JSON output specification that MUST be followed):\n"
                + userQuery + "\n\n"
                + "STAGE 1 - Individual Responses:\n"
                + stage1Text + "\n"
                + "STAGE 2 - Peer Rankings:\n"
                + stage2Text + "\n"
                + "Your task as Chairman is to synthesize all of this information into a single, comprehensive, accurate answer to the user's original question.\n"
                + "You MUST output exactly one JSON object that strictly follows the JSON structure and field names specified in the Original Question. "
                + "Do not output any extra text, comments, or explanations outside of the JSON.\n\n"
                + "Focus on:\n"
                + "- The individual responses and their insights\n"
                + "- The peer rankings and what they reveal about response quality\n"
                + "- Any patterns of agreement or disagreement\n\n"
                + "Return only the final JSON.";
    }

    private CompletableFuture<ModelResponse> queryModel(String model, String userContent) {
        if (apiKey == null || apiKey.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("model", model);

        List<Map<String, String>> messages = new ArrayList<>();
        Map<String, String> msg = new HashMap<>();
        msg.put("role", "user");
        msg.put("content", userContent);
        messages.add(msg);
        payload.put("messages", messages);

        log.info("Querying model: {}", model);
        return webClient.post()
                .uri(apiUrl)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(String.class)
                .publishOn(Schedulers.fromExecutor(llmCouncilExecutor))
                .mapNotNull(body -> parseModelResponse(model, body))
                .onErrorResume(e -> {
                    log.error("LLM模型调用失败, model={}", model, e);
                    return reactor.core.publisher.Mono.just(null);
                })
                .toFuture();
    }

    private ModelResponse parseModelResponse(String model, String body) {
        try {
            JsonNode root = mapper.readTree(body);
            if (root.has("error")) {
                return null;
            }
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return null;
            }
            JsonNode message = choices.get(0).path("message");
            String content = message.path("content").asText("");
            ModelResponse r = new ModelResponse();
            r.model = model;
            r.content = content;
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> parseRankingFromText(String rankingText) {
        if (rankingText == null || rankingText.isEmpty()) {
            return Collections.emptyList();
        }
        String marker = "FINAL RANKING:";
        int idx = rankingText.indexOf(marker);
        if (idx < 0) {
            return Collections.emptyList();
        }
        String section = rankingText.substring(idx + marker.length());
        Pattern p = Pattern.compile("\\d+\\.\\s*Response\\s+[A-Z]");
        Matcher m = p.matcher(section);
        List<String> labels = new ArrayList<>();
        while (m.find()) {
            String match = m.group();
            int pos = match.indexOf("Response");
            if (pos >= 0) {
                String label = match.substring(pos).trim();
                labels.add(label);
            }
        }
        return labels;
    }

    private static class ModelResponse {
        String model;
        String content;
    }

    private static class ModelRanking {
        String model;
        String rankingResponse;
        List<String> parsedRanking = Collections.emptyList();
    }

    private static class Stage2Result {
        List<ModelRanking> stage2Results;
        Map<String, String> labelToModel;

        Stage2Result(List<ModelRanking> stage2Results, Map<String, String> labelToModel) {
            this.stage2Results = stage2Results;
            this.labelToModel = labelToModel;
        }
    }
}
