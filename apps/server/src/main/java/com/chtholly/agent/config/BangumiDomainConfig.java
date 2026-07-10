package com.chtholly.agent.config;

import java.util.List;

/** Bangumi query parsing and result formatting configuration. */
public record BangumiDomainConfig(
        String description,
        String keywordParam,
        String missingKeyword,
        String noSubjectResult,
        String noAnimeResult,
        String seriesResultTemplate,
        String seasonQuestionRegex,
        String shortSeriesRegex,
        String shortSeriesSuffixRegex,
        String titleStopRegex,
        String titlePrefixRegex,
        String titleSuffixRegex,
        List<String> searchKeywords,
        String resultTemplate,
        String displayNameTemplate,
        String itemPrefix,
        String typeLabel,
        String scoreLabel,
        String rankLabel,
        String episodesLabel,
        String airDateLabel,
        String summaryLabel,
        String truncatedSuffix,
        String unknownType,
        String bookType,
        String animeType,
        String musicType,
        String gameType,
        String realType,
        String fallbackTypePrefix
) { }
