package com.chtholly.agent.config;

/** Labels and parsing expressions used for conversation context. */
public record AgentContextLabels(
        String timeLabel,
        String userLabel,
        String pageLabel,
        String assistantLabel,
        String observationLabel,
        String currentQuestionHeading,
        String quotedTitleRegex,
        String titleStopRegex,
        String topicPrefixRegex,
        String topicSuffixRegex,
        String clauseSplitRegex,
        String commaMarker
) { }
