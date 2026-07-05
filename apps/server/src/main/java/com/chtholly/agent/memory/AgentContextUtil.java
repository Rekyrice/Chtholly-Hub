package com.chtholly.agent.memory;

import com.chtholly.agent.config.AgentDomainConfig;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts likely work title candidates from conversation history and user questions. */
public final class AgentContextUtil {

    private AgentContextUtil() {
    }

    /** Merges candidates from conversation history and the current user question. */
    public static List<String> extractWorkTitleCandidates(
            String history,
            String userQuestion,
            AgentDomainConfig agentDomainConfig) {
        Set<String> candidates = new LinkedHashSet<>();
        if (StringUtils.hasText(history)) {
            addQuotedTitles(candidates, history, agentDomainConfig);
            for (String line : history.split("\n")) {
                if (line.startsWith(agentDomainConfig.getContext().getUserLabel())) {
                    addTopicFromQuestion(candidates,
                            line.substring(agentDomainConfig.getContext().getUserLabel().length()).trim(),
                            agentDomainConfig);
                }
            }
            addCommaSeparatedFragment(candidates, history, agentDomainConfig);
        }
        if (StringUtils.hasText(userQuestion)) {
            addQuotedTitles(candidates, userQuestion, agentDomainConfig);
            addTopicFromQuestion(candidates, userQuestion, agentDomainConfig);
        }
        return new ArrayList<>(candidates);
    }

    private static void addTopicFromQuestion(
            Set<String> candidates,
            String question,
            AgentDomainConfig agentDomainConfig) {
        if (!StringUtils.hasText(question)) {
            return;
        }
        String topic = question.trim()
                .replaceAll(agentDomainConfig.getContext().getTitleStopRegex(), "")
                .replaceAll(agentDomainConfig.getContext().getTopicPrefixRegex(), "")
                .replaceAll(agentDomainConfig.getContext().getTopicSuffixRegex(), "")
                .trim();
        if (topic.length() >= 2 && topic.length() <= 30) {
            candidates.add(topic);
        }
    }

    private static void addQuotedTitles(
            Set<String> candidates,
            String text,
            AgentDomainConfig agentDomainConfig) {
        Matcher m = Pattern.compile(agentDomainConfig.getContext().getQuotedTitleRegex()).matcher(text);
        while (m.find()) {
            for (int i = 1; i <= m.groupCount(); i++) {
                if (StringUtils.hasText(m.group(i))) {
                    candidates.add(m.group(i).trim());
                }
            }
        }
    }

    /** Extracts short comma-separated title fragments from recent turns. */
    private static void addCommaSeparatedFragment(
            Set<String> candidates,
            String text,
            AgentDomainConfig agentDomainConfig) {
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(agentDomainConfig.getContext().getAssistantLabel())) {
                trimmed = trimmed.substring(agentDomainConfig.getContext().getAssistantLabel().length()).trim();
            } else if (trimmed.startsWith(agentDomainConfig.getContext().getUserLabel())) {
                trimmed = trimmed.substring(agentDomainConfig.getContext().getUserLabel().length()).trim();
            }
            if (trimmed.contains(agentDomainConfig.getContext().getCommaMarker())
                    && trimmed.length() >= 4
                    && trimmed.length() <= 40) {
                int comma = trimmed.indexOf(agentDomainConfig.getContext().getCommaMarker());
                if (comma > 1) {
                    candidates.add(trimmed.substring(0, comma + 1)
                            + trimmed.substring(comma + 1)
                            .split(agentDomainConfig.getContext().getClauseSplitRegex())[0]);
                }
            }
            if (trimmed.length() >= 3 && trimmed.length() <= 24) {
                String firstClause = trimmed.split(agentDomainConfig.getContext().getClauseSplitRegex())[0].trim();
                if (firstClause.length() >= 3) {
                    candidates.add(firstClause);
                }
            }
        }
    }
}
