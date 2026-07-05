package com.chtholly.agent.tools;

import com.chtholly.agent.AgentTool;
import com.chtholly.agent.ParamDef;
import com.chtholly.agent.config.AgentDomainConfig;
import com.chtholly.agent.memory.AgentContextUtil;
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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Bangumi subject search tool backed by local cache and API fallback. */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
@RequiredArgsConstructor
public class BangumiSearchTool implements AgentTool {

    private final BangumiService bangumiService;
    private final AgentDomainConfig agentDomainConfig;

    @Override
    public String name() {
        return "bangumi_search";
    }

    @Override
    public String description() {
        return agentDomainConfig.getBangumi().getDescription();
    }

    @Override
    public Map<String, ParamDef> parameterSchema() {
        return Map.of(
                "keyword", new ParamDef(agentDomainConfig.getBangumi().getKeywordParam(), String.class, true)
        );
    }

    @Override
    public String execute(Map<String, Object> input, long userId) {
        List<String> keywords = buildKeywordCandidates(input);
        if (keywords.isEmpty()) {
            return agentDomainConfig.getBangumi().getMissingKeyword();
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
        return agentDomainConfig.render(
                agentDomainConfig.getBangumi().getNoSubjectResult(),
                "keyword", keywords.get(0));
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
            return agentDomainConfig.render(
                    agentDomainConfig.getBangumi().getNoAnimeResult(),
                    "keyword", keywords.get(0));
        }

        List<BangumiSubjectRow> rows = new ArrayList<>(merged.values());
        String header = agentDomainConfig.render(
                agentDomainConfig.getBangumi().getSeriesResultTemplate(),
                "count", rows.size());
        String body = rows.stream().map(this::formatSubject).collect(Collectors.joining("\n\n"));
        return header + "\n\n" + body;
    }

    private boolean isSeasonQuestion(Map<String, Object> input) {
        Object q = input.get("_userQuestion");
        if (q == null) {
            return false;
        }
        return Pattern.compile(agentDomainConfig.getBangumi().getSeasonQuestionRegex())
                .matcher(String.valueOf(q))
                .find();
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
        for (String title : AgentContextUtil.extractWorkTitleCandidates(history, userQuestion, agentDomainConfig)) {
            candidates.add(title);
            candidates.add(shortSeriesName(title));
        }
    }

    /** Extracts likely title candidates after removing season/rating question suffixes. */
    private void addTitleCandidates(Set<String> candidates, String question) {
        String title = extractTitle(question);
        if (StringUtils.hasText(title)) {
            candidates.add(title);
            candidates.add(shortSeriesName(title));
        }
    }

    /** Removes season markers so the series search can recall multiple related entries. */
    private String shortSeriesName(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return keyword;
        }
        String s = keyword.trim();
        s = s.replaceAll(agentDomainConfig.getBangumi().getShortSeriesRegex(), "");
        s = s.replaceAll(agentDomainConfig.getBangumi().getShortSeriesSuffixRegex(), "");
        if (s.length() >= 2 && s.length() < keyword.length()) {
            return s;
        }
        return keyword;
    }

    private String extractTitle(String question) {
        if (!StringUtils.hasText(question)) {
            return "";
        }
        return question.trim()
                .replaceAll(agentDomainConfig.getBangumi().getTitleStopRegex(), "")
                .replaceAll(agentDomainConfig.getBangumi().getTitlePrefixRegex(), "")
                .replaceAll(agentDomainConfig.getBangumi().getTitleSuffixRegex(), "")
                .trim();
    }

    private String formatSubject(BangumiSubjectRow row) {
        String displayName = row.getNameCn() != null && !row.getNameCn().isBlank()
                ? agentDomainConfig.render(
                agentDomainConfig.getBangumi().getDisplayNameTemplate(),
                "nameCn", row.getNameCn(),
                "name", row.getName())
                : row.getName();
        StringBuilder sb = new StringBuilder();
        sb.append(agentDomainConfig.render(
                agentDomainConfig.getBangumi().getItemPrefix(),
                "displayName", displayName));
        sb.append(" [Bangumi ").append(row.getId()).append("]");
        sb.append("\n  ").append(agentDomainConfig.getBangumi().getTypeLabel()).append(typeLabel(row.getType()));
        if (row.getScore() != null) {
            sb.append(" | ").append(agentDomainConfig.getBangumi().getScoreLabel()).append(row.getScore());
        }
        if (row.getRank() != null && row.getRank() > 0) {
            sb.append(" | ").append(agentDomainConfig.getBangumi().getRankLabel()).append(row.getRank());
        }
        if (row.getEpsCount() != null) {
            sb.append(" | ").append(agentDomainConfig.getBangumi().getEpisodesLabel()).append(row.getEpsCount());
        }
        if (row.getAirDate() != null) {
            sb.append(" | ").append(agentDomainConfig.getBangumi().getAirDateLabel()).append(row.getAirDate());
        }
        if (row.getSummary() != null && !row.getSummary().isBlank()) {
            String summary = row.getSummary().strip();
            if (summary.length() > 180) {
                summary = summary.substring(0, 180) + agentDomainConfig.getBangumi().getTruncatedSuffix();
            }
            sb.append("\n  ").append(agentDomainConfig.getBangumi().getSummaryLabel()).append(summary);
        }
        return sb.toString();
    }

    private String typeLabel(Integer type) {
        if (type == null) {
            return agentDomainConfig.getBangumi().getUnknownType();
        }
        return switch (type) {
            case 1 -> agentDomainConfig.getBangumi().getBookType();
            case 2 -> agentDomainConfig.getBangumi().getAnimeType();
            case 3 -> agentDomainConfig.getBangumi().getMusicType();
            case 4 -> agentDomainConfig.getBangumi().getGameType();
            case 6 -> agentDomainConfig.getBangumi().getRealType();
            default -> agentDomainConfig.getBangumi().getFallbackTypePrefix() + type;
        };
    }
}
