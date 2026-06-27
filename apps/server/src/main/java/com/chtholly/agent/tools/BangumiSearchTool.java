package com.chtholly.agent.tools;

import com.chtholly.agent.memory.AgentContextUtil;
import com.chtholly.agent.AgentTool;
import com.chtholly.bangumi.model.BangumiSubjectRow;
import com.chtholly.bangumi.service.BangumiService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Bangumi 番剧搜索：本地缓存 + API 回填。 */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
@RequiredArgsConstructor
public class BangumiSearchTool implements AgentTool {

    private final BangumiService bangumiService;

    @Override
    public String name() {
        return "bangumi_search";
    }

    @Override
    public String description() {
        return """
                搜索 Bangumi 条目（动画/漫画/游戏等）的评分、集数、放送日。
                问「有几季/多少季」时会自动宽召回同系列全部动画条目。
                input: {"keyword":"条目名或系列简称"}""";
    }

    @Override
    public String execute(Map<String, Object> input, long userId) {
        List<String> keywords = buildKeywordCandidates(input);
        if (keywords.isEmpty()) {
            return "错误：缺少参数 keyword";
        }

        if (isSeasonQuestion(input)) {
            return searchSeries(keywords);
        }

        IllegalStateException lastApiError = null;
        for (String keyword : keywords) {
            try {
                List<BangumiSubjectRow> items = bangumiService.search(keyword, 5);
                if (!items.isEmpty()) {
                    return items.stream().map(this::formatSubject).collect(Collectors.joining("\n\n"));
                }
            } catch (IllegalStateException e) {
                lastApiError = e;
            }
        }

        if (lastApiError != null) {
            return lastApiError.getMessage();
        }
        return "Bangumi 未找到与「" + keywords.get(0) + "」相关的条目。";
    }

    private String searchSeries(List<String> keywords) {
        Map<Long, BangumiSubjectRow> merged = new LinkedHashMap<>();
        IllegalStateException lastApiError = null;

        for (String keyword : keywords) {
            try {
                for (BangumiSubjectRow row : bangumiService.searchAnimeSeries(keyword, 15)) {
                    merged.putIfAbsent(row.getId(), row);
                }
            } catch (IllegalStateException e) {
                lastApiError = e;
            }
        }

        if (merged.isEmpty()) {
            if (lastApiError != null) {
                return lastApiError.getMessage();
            }
            return "Bangumi 未找到与「" + keywords.get(0) + "」相关的动画条目。";
        }

        List<BangumiSubjectRow> rows = new ArrayList<>(merged.values());
        String header = "共找到 " + rows.size() + " 部相关动画条目（按放送日排序，请据此统计季数）：";
        String body = rows.stream().map(this::formatSubject).collect(Collectors.joining("\n\n"));
        return header + "\n\n" + body;
    }

    private boolean isSeasonQuestion(Map<String, Object> input) {
        Object q = input.get("_userQuestion");
        if (q == null) {
            return false;
        }
        String s = String.valueOf(q);
        return s.contains("几季") || s.contains("季数") || s.contains("多少季") || s.contains("一共几季");
    }

    private List<String> buildKeywordCandidates(Map<String, Object> input) {
        Set<String> candidates = new LinkedHashSet<>();
        String keyword = input.get("keyword") == null ? "" : String.valueOf(input.get("keyword")).trim();
        if (StringUtils.hasText(keyword)) {
            candidates.add(keyword);
            candidates.add(shortSeriesName(keyword));
        }
        Object userQuestion = input.get("_userQuestion");
        if (userQuestion != null) {
            addTitleCandidates(candidates, String.valueOf(userQuestion));
        }
        Object history = input.get("_conversationHistory");
        if (history != null) {
            addFromConversationHistory(candidates, String.valueOf(history), String.valueOf(userQuestion == null ? "" : userQuestion));
        }
        return new ArrayList<>(candidates);
    }

    private void addFromConversationHistory(Set<String> candidates, String history, String userQuestion) {
        if (!StringUtils.hasText(history)) {
            return;
        }
        for (String title : AgentContextUtil.extractWorkTitleCandidates(history, userQuestion)) {
            candidates.add(title);
            candidates.add(shortSeriesName(title));
        }
    }

    /** 提取系列简称，便于宽召回多季（如「盾之勇者成名录」→「盾之勇者」）。 */
    private String shortSeriesName(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return keyword;
        }
        String s = keyword.trim();
        s = s.replaceAll("(第一季|第二季|第三季|第四季|第五季|Season\\s*\\d+).*$", "");
        s = s.replaceAll("(的成.*|成名录.*|第二季.*|第三季.*)$", "");
        if (s.length() >= 2 && s.length() < keyword.length()) {
            return s;
        }
        return keyword;
    }

    private void addTitleCandidates(Set<String> candidates, String question) {
        String title = extractTitle(question);
        if (StringUtils.hasText(title)) {
            candidates.add(title);
            candidates.add(shortSeriesName(title));
        }
        if (title.contains("盾之勇者") || question.contains("盾之勇者")) {
            candidates.add("盾之勇者");
        }
        if (title.contains("高木")) {
            candidates.add("擅长捉弄的高木同学");
            candidates.add("高木同学");
        }
        String lower = title.toLowerCase();
        if (lower.contains("re0") || lower.contains("re:") || title.contains("从零开始")) {
            candidates.add("Re:从零开始的异世界生活");
            candidates.add("Re:ZERO");
            candidates.add("Re0");
        }
    }

    private String extractTitle(String question) {
        if (!StringUtils.hasText(question)) {
            return "";
        }
        return question.trim()
                .replaceAll("[？?。！!，,；;].*$", "")
                .replaceAll("^(查找|搜索|查一下|帮我查|帮我搜|查询|请问|告诉我|想知道|看看)", "")
                .replaceAll("(一共有|有几|几季|季数|多少季|的评分|评分多少|多少分|有多少集|集数|怎么样|是什么|信息).*$", "")
                .trim();
    }

    private String formatSubject(BangumiSubjectRow row) {
        String displayName = row.getNameCn() != null && !row.getNameCn().isBlank()
                ? row.getNameCn() + "（" + row.getName() + "）"
                : row.getName();
        StringBuilder sb = new StringBuilder();
        sb.append("- 《").append(displayName).append("》");
        sb.append(" [Bangumi ").append(row.getId()).append("]");
        sb.append("\n  类型：").append(typeLabel(row.getType()));
        if (row.getScore() != null) {
            sb.append(" | 评分：").append(row.getScore());
        }
        if (row.getRank() != null && row.getRank() > 0) {
            sb.append(" | 排名：").append(row.getRank());
        }
        if (row.getEpsCount() != null) {
            sb.append(" | 集数：").append(row.getEpsCount());
        }
        if (row.getAirDate() != null) {
            sb.append(" | 放送/发售：").append(row.getAirDate());
        }
        if (row.getSummary() != null && !row.getSummary().isBlank()) {
            String summary = row.getSummary().strip();
            if (summary.length() > 180) {
                summary = summary.substring(0, 180) + "…";
            }
            sb.append("\n  简介：").append(summary);
        }
        return sb.toString();
    }

    private String typeLabel(Integer type) {
        if (type == null) {
            return "未知";
        }
        return switch (type) {
            case 1 -> "书籍";
            case 2 -> "动画";
            case 3 -> "音乐";
            case 4 -> "游戏";
            case 6 -> "三次元";
            default -> "类型" + type;
        };
    }
}
