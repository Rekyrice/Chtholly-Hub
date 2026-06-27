package com.chtholly.agent.memory;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从对话历史与用户问题中提取作品名、主题词，供工具与守卫逻辑复用。 */
public final class AgentContextUtil {

    private static final Pattern WORK_TITLE = Pattern.compile(
            "《([^》]+)》|「([^」]+)」|“([^”]+)”|\"([^\"]+)\"");

    private AgentContextUtil() {
    }

    /** 合并历史与当前问题中的作品/条目名候选（去重、保序）。 */
    public static List<String> extractWorkTitleCandidates(String history, String userQuestion) {
        Set<String> candidates = new LinkedHashSet<>();
        if (StringUtils.hasText(history)) {
            addQuotedTitles(candidates, history);
            for (String line : history.split("\n")) {
                if (line.startsWith("User:")) {
                    addTopicFromQuestion(candidates, line.substring("User:".length()).trim());
                }
            }
            addPlainTitleHints(candidates, history);
        }
        if (StringUtils.hasText(userQuestion)) {
            addQuotedTitles(candidates, userQuestion);
            addTopicFromQuestion(candidates, userQuestion);
        }
        return new ArrayList<>(candidates);
    }

    private static void addTopicFromQuestion(Set<String> candidates, String question) {
        if (!StringUtils.hasText(question)) {
            return;
        }
        String topic = question.trim()
                .replaceAll("[？?。！!，,；;].*$", "")
                .replaceAll("^(请问|告诉我|想知道|帮我查|查一下|搜索)", "")
                .replaceAll("(的主要人物|主要人物|的人物|有哪些人物|有哪些角色|的角色|的伙伴|宿舍伙伴|宿舍的伙伴|伙伴都有|都有谁|是谁|怎么样|是什么).*$", "")
                .trim();
        if (topic.length() >= 2 && topic.length() <= 30) {
            candidates.add(topic);
        }
    }

    /** 对话是否涉及动漫/漫画条目（用于短追问判定）。 */
    public static boolean historyMentionsBangumiTopic(String history) {
        if (!StringUtils.hasText(history)) {
            return false;
        }
        String h = history;
        return h.contains("Bangumi")
                || h.contains("《")
                || h.contains("评分")
                || h.contains("人物")
                || h.contains("角色")
                || h.contains("作者")
                || h.contains("漫画")
                || h.contains("番剧")
                || h.contains("动画");
    }

    private static void addQuotedTitles(Set<String> candidates, String text) {
        Matcher m = WORK_TITLE.matcher(text);
        while (m.find()) {
            for (int i = 1; i <= m.groupCount(); i++) {
                if (StringUtils.hasText(m.group(i))) {
                    candidates.add(m.group(i).trim());
                }
            }
        }
    }

    /** 从 Assistant 回答或 User 问题中提取未加书名号的长标题片段。 */
    private static void addPlainTitleHints(Set<String> candidates, String text) {
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Assistant:")) {
                trimmed = trimmed.substring("Assistant:".length()).trim();
            } else if (trimmed.startsWith("User:")) {
                trimmed = trimmed.substring("User:".length()).trim();
            }
            if (trimmed.contains("，") && trimmed.length() >= 4 && trimmed.length() <= 40) {
                int comma = trimmed.indexOf('，');
                if (comma > 1) {
                    candidates.add(trimmed.substring(0, comma + 1) + trimmed.substring(comma + 1).split("[，。！？?]")[0]);
                }
            }
            if (trimmed.length() >= 3 && trimmed.length() <= 24
                    && (trimmed.contains("旅行") || trimmed.contains("勇者") || trimmed.contains("牡丹"))) {
                candidates.add(trimmed.split("[，。！？?]")[0].trim());
            }
        }
    }
}
