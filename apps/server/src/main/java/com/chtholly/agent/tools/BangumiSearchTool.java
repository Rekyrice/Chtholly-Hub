package com.chtholly.agent.tools;

import com.chtholly.agent.AgentTool;
import com.chtholly.bangumi.model.BangumiSubjectRow;
import com.chtholly.bangumi.service.BangumiService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
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
        return "搜索 Bangumi 番剧元数据（评分、集数、放送日等）。input: {\"keyword\":\"番剧名\"}";
    }

    @Override
    public String execute(Map<String, Object> input, long userId) {
        List<String> keywords = buildKeywordCandidates(input);
        if (keywords.isEmpty()) {
            return "错误：缺少参数 keyword";
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

    private List<String> buildKeywordCandidates(Map<String, Object> input) {
        Set<String> candidates = new LinkedHashSet<>();
        String keyword = input.get("keyword") == null ? "" : String.valueOf(input.get("keyword")).trim();
        if (StringUtils.hasText(keyword)) {
            candidates.add(keyword);
        }
        Object userQuestion = input.get("_userQuestion");
        if (userQuestion != null) {
            addTitleCandidates(candidates, String.valueOf(userQuestion));
        }
        return new ArrayList<>(candidates);
    }

    private void addTitleCandidates(Set<String> candidates, String question) {
        String title = extractTitle(question);
        if (StringUtils.hasText(title)) {
            candidates.add(title);
        }
        // 常见简称回退
        if (title.contains("高木")) {
            candidates.add("擅长捉弄的高木同学");
            candidates.add("高木同学");
        }
    }

    private String extractTitle(String question) {
        if (!StringUtils.hasText(question)) {
            return "";
        }
        return question.trim()
                .replaceAll("[？?。！!，,；;].*$", "")
                .replaceAll("^(查找|搜索|查一下|帮我查|帮我搜|查询|请问|告诉我|想知道|看看)", "")
                .replaceAll("(的评分|评分多少|多少分|有多少集|集数|怎么样|是什么|信息).*$", "")
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
