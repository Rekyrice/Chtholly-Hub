package com.chtholly.agent.eval;

import org.springframework.ai.chat.client.ChatClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM-as-judge client with prompt construction and strict text parsing.
 */
public class LlmJudgeClient implements JudgeClient {

    private static final Pattern SCORE_LINE =
            Pattern.compile("^([a-z_]+):\\s*([1-5])(?:\\.\\d+)?\\s*[—-]\\s*(.+)$");
    private static final Pattern OVERALL_LINE =
            Pattern.compile("^overall:\\s*([0-5](?:\\.\\d+)?)\\s*$");

    private final Function<String, String> completion;

    public LlmJudgeClient(ChatClient chatClient) {
        this(prompt -> chatClient.prompt().user(prompt).call().content());
    }

    public LlmJudgeClient(Function<String, String> completion) {
        this.completion = completion;
    }

    @Override
    public EvaluationScores judge(JudgeRequest request) {
        return parseScores(completion.apply(buildPrompt(request)));
    }

    static EvaluationScores parseScores(String output) {
        Map<String, EvaluationScore> scores = new LinkedHashMap<>();
        double overall = 0.0;
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            Matcher scoreMatcher = SCORE_LINE.matcher(trimmed);
            if (scoreMatcher.matches()) {
                scores.put(scoreMatcher.group(1),
                        new EvaluationScore(Integer.parseInt(scoreMatcher.group(2)), scoreMatcher.group(3)));
                continue;
            }
            Matcher overallMatcher = OVERALL_LINE.matcher(trimmed);
            if (overallMatcher.matches()) {
                overall = Double.parseDouble(overallMatcher.group(1));
            }
        }
        if (overall == 0.0 && !scores.isEmpty()) {
            overall = scores.values().stream().mapToInt(EvaluationScore::score).average().orElse(0.0);
        }
        return new EvaluationScores(scores, EvaluationReport.round(overall));
    }

    private String buildPrompt(JudgeRequest request) {
        int dimensionCount = request.dimensions().size();
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个专业的 AI 对话质量评估员。请根据以下 ")
                .append(dimensionCount)
                .append(" 个维度对珂朵莉的回复打分（1-5 分）。\n\n");
        sb.append("用户问题：").append(request.question().text()).append("\n");
        sb.append("珂朵莉回复：").append(request.response()).append("\n");
        sb.append("用户资料：").append(request.question().userProfile()).append("\n");
        sb.append("对话历史摘要：").append(request.question().historySummary()).append("\n\n");
        sb.append("评分维度：\n");
        for (EvaluationDimension dimension : request.dimensions()) {
            sb.append("- ").append(dimension.key()).append("：")
                    .append(dimension.description()).append(" ")
                    .append(dimension.rubric()).append("\n");
        }
        sb.append("\n请按以下格式输出：\n");
        for (String key : request.dimensionKeys()) {
            sb.append(key).append(": [分数] — [理由]\n");
        }
        sb.append("overall: [加权平均分]\n");
        return sb.toString();
    }
}
