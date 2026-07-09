package com.chtholly.agent.eval;

import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对同一问题的多次回复做风格一致性 LLM 评分。
 */
public class ConsistencyJudgeClient {

    private static final Pattern SCORE_LINE =
            Pattern.compile("^style_consistency:\\s*([1-5])(?:\\.\\d+)?\\s*[—-]\\s*(.+)$");

    private final Function<String, String> completion;

    public ConsistencyJudgeClient(ChatClient chatClient) {
        this(prompt -> chatClient.prompt().user(prompt).call().content());
    }

    public ConsistencyJudgeClient(Function<String, String> completion) {
        this.completion = completion;
    }

    /**
     * 判断多次回复是否像同一个人说的。
     */
    public EvaluationScore judge(List<String> responses) {
        if (responses == null || responses.size() < 2) {
            return new EvaluationScore(1, "回复样本不足，无法判断一致性");
        }
        return parseScore(completion.apply(buildPrompt(responses)));
    }

    static EvaluationScore parseScore(String output) {
        for (String line : output.split("\\R")) {
            Matcher matcher = SCORE_LINE.matcher(line.trim());
            if (matcher.matches()) {
                return new EvaluationScore(Integer.parseInt(matcher.group(1)), matcher.group(2));
            }
        }
        return new EvaluationScore(1, "无法解析一致性评分");
    }

    private static String buildPrompt(List<String> responses) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 AI 对话质量评估员。下面是对同一用户问题的多次回复，请判断它们是否像同一个人说的。\n");
        sb.append("评分维度：人格一致性 / 风格稳定性。\n");
        sb.append("rubric: 5=语气、用词、态度高度一致；3=大体一致但细节有波动；1=像不同 AI 或人格分裂。\n\n");
        for (int i = 0; i < responses.size(); i++) {
            sb.append("回复 ").append(i + 1).append("：").append(responses.get(i)).append("\n");
        }
        sb.append("\n请只输出一行：style_consistency: [1-5] — [理由]\n");
        return sb.toString();
    }
}
