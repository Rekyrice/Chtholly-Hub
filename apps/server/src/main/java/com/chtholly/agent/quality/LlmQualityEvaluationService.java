package com.chtholly.agent.quality;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * LLM-backed quality evaluator with heuristic fallback.
 */
@Slf4j
@Primary
@Component
public class LlmQualityEvaluationService implements QualityEvaluationService {

    private final ObjectProvider<ChatClient> chatClientProvider;
    private final HeuristicQualityEvaluationService fallback;
    private final ObjectMapper objectMapper;

    public LlmQualityEvaluationService(ObjectProvider<ChatClient> chatClientProvider,
                                       HeuristicQualityEvaluationService fallback,
                                       ObjectMapper objectMapper) {
        this.chatClientProvider = chatClientProvider;
        this.fallback = fallback;
        this.objectMapper = objectMapper;
    }

    /**
     * Evaluates content through LLM and falls back to deterministic rules.
     *
     * @param content  Text content to evaluate.
     * @param context  Additional context such as title, tags, or persona.
     * @param criteria Evaluation criteria.
     * @return evaluation result.
     */
    @Override
    public QualityResult evaluate(String content, String context, QualityCriteria criteria) {
        QualityCriteria safeCriteria = criteria == null ? QualityCriteria.articleQuality() : criteria;
        QualityResult fallbackResult = fallback.evaluate(content, context, safeCriteria);
        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            return fallbackResult;
        }

        try {
            String raw = chatClient.prompt()
                    .user(buildPrompt(content, context, safeCriteria))
                    .call()
                    .content();
            if (raw == null || raw.isBlank()) {
                return fallbackResult;
            }
            LlmQualityDraft draft = objectMapper.readValue(extractJsonObject(raw), LlmQualityDraft.class);
            double score = clamp(draft.score());
            Map<String, Double> dimensionScores = mergeDimensionScores(draft.dimensionScores(), score, safeCriteria);
            String feedback = draft.feedback() == null || draft.feedback().isBlank()
                    ? fallbackResult.feedback()
                    : draft.feedback().trim();
            return new QualityResult(score, feedback, score >= safeCriteria.minScore(), dimensionScores);
        } catch (Exception e) {
            log.warn("LLM quality evaluation failed, using heuristic fallback: {}", e.getMessage());
            return fallbackResult;
        }
    }

    private static String buildPrompt(String content, String context, QualityCriteria criteria) {
        String dimensions = criteria.dimensions().stream()
                .map(dimension -> "- %s %.2f".formatted(dimension.name(), dimension.weight()))
                .collect(Collectors.joining("\n"));
        return """
                请评估以下内容质量，输出 JSON，不要输出额外说明。

                评分范围：1-5 分。
                最低通过分：%.1f
                评分维度：
                %s

                上下文：
                %s

                待评内容：
                %s

                输出格式：
                {
                  "score": 4.2,
                  "feedback": "一句简短反馈",
                  "dimensionScores": {
                    "维度名": 4.0
                  }
                }
                """.formatted(criteria.minScore(), dimensions, nullToBlank(context), nullToBlank(content));
    }

    private static Map<String, Double> mergeDimensionScores(
            Map<String, Double> rawScores,
            double defaultScore,
            QualityCriteria criteria) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (QualityCriteria.Dimension dimension : criteria.dimensions()) {
            Double raw = rawScores == null ? null : rawScores.get(dimension.name());
            scores.put(dimension.name(), raw == null ? defaultScore : clamp(raw));
        }
        return scores;
    }

    private static String extractJsonObject(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return raw;
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private static double clamp(double score) {
        return Math.max(1.0, Math.min(5.0, score));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record LlmQualityDraft(
            double score,
            String feedback,
            Map<String, Double> dimensionScores
    ) {
    }
}
