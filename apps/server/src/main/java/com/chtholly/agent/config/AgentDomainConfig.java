package com.chtholly.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Domain dialogue configuration for Chtholly agent prompts and tool messages.
 *
 * <p>All user-visible Chinese dialogue fragments live in {@code agent-domain.yml}
 * so prompts, fallback messages, and Bangumi formatting can evolve without Java edits.
 */
@Configuration
@ConfigurationProperties(prefix = "agent.domain")
public class AgentDomainConfig {

    private SystemPrompt systemPrompt = new SystemPrompt();
    private ErrorMessages errors = new ErrorMessages();
    private BangumiConfig bangumi = new BangumiConfig();
    private ContextLabels context = new ContextLabels();

    public SystemPrompt getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(SystemPrompt systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public ErrorMessages getErrors() {
        return errors;
    }

    public void setErrors(ErrorMessages errors) {
        this.errors = errors;
    }

    public BangumiConfig getBangumi() {
        return bangumi;
    }

    public void setBangumi(BangumiConfig bangumi) {
        this.bangumi = bangumi;
    }

    public ContextLabels getContext() {
        return context;
    }

    public void setContext(ContextLabels context) {
        this.context = context;
    }

    public String render(String template, Object... keyValues) {
        String result = template == null ? "" : template;
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            result = result.replace("{" + keyValues[i] + "}", String.valueOf(keyValues[i + 1]));
        }
        return result;
    }

    public static class SystemPrompt {
        private String errorFallback = "";
        private String parseErrorObservation = "";
        private String parseErrorThink = "";
        private String finalAnswerSystem = "";
        private String finalAnswerPrompt = "";
        private String finalThinking = "";
        private String toolThinking = "";
        private String siteEmptyGuidance = "";
        private String bangumiTimeoutGuidance = "";
        private List<String> emptySiteResultMarkers = new ArrayList<>();

        public String getErrorFallback() {
            return errorFallback;
        }

        public void setErrorFallback(String errorFallback) {
            this.errorFallback = errorFallback;
        }

        public String getParseErrorObservation() {
            return parseErrorObservation;
        }

        public void setParseErrorObservation(String parseErrorObservation) {
            this.parseErrorObservation = parseErrorObservation;
        }

        public String getParseErrorThink() {
            return parseErrorThink;
        }

        public void setParseErrorThink(String parseErrorThink) {
            this.parseErrorThink = parseErrorThink;
        }

        public String getFinalAnswerSystem() {
            return finalAnswerSystem;
        }

        public void setFinalAnswerSystem(String finalAnswerSystem) {
            this.finalAnswerSystem = finalAnswerSystem;
        }

        public String getFinalAnswerPrompt() {
            return finalAnswerPrompt;
        }

        public void setFinalAnswerPrompt(String finalAnswerPrompt) {
            this.finalAnswerPrompt = finalAnswerPrompt;
        }

        public String getFinalThinking() {
            return finalThinking;
        }

        public void setFinalThinking(String finalThinking) {
            this.finalThinking = finalThinking;
        }

        public String getToolThinking() {
            return toolThinking;
        }

        public void setToolThinking(String toolThinking) {
            this.toolThinking = toolThinking;
        }

        public String getSiteEmptyGuidance() {
            return siteEmptyGuidance;
        }

        public void setSiteEmptyGuidance(String siteEmptyGuidance) {
            this.siteEmptyGuidance = siteEmptyGuidance;
        }

        public String getBangumiTimeoutGuidance() {
            return bangumiTimeoutGuidance;
        }

        public void setBangumiTimeoutGuidance(String bangumiTimeoutGuidance) {
            this.bangumiTimeoutGuidance = bangumiTimeoutGuidance;
        }

        public List<String> getEmptySiteResultMarkers() {
            return emptySiteResultMarkers;
        }

        public void setEmptySiteResultMarkers(List<String> emptySiteResultMarkers) {
            this.emptySiteResultMarkers = emptySiteResultMarkers;
        }
    }

    public static class ErrorMessages {
        private String questionEmpty = "";
        private String modelResponseTimeout = "";
        private String modelCallFailed = "";
        private String responseTimeout = "";
        private String responseFailed = "";
        private String maxSteps = "";
        private String unknownTool = "";
        private String toolFailed = "";
        private String toolInterrupted = "";
        private String noResult = "";

        public String getQuestionEmpty() {
            return questionEmpty;
        }

        public void setQuestionEmpty(String questionEmpty) {
            this.questionEmpty = questionEmpty;
        }

        public String getModelResponseTimeout() {
            return modelResponseTimeout;
        }

        public void setModelResponseTimeout(String modelResponseTimeout) {
            this.modelResponseTimeout = modelResponseTimeout;
        }

        public String getModelCallFailed() {
            return modelCallFailed;
        }

        public void setModelCallFailed(String modelCallFailed) {
            this.modelCallFailed = modelCallFailed;
        }

        public String getResponseTimeout() {
            return responseTimeout;
        }

        public void setResponseTimeout(String responseTimeout) {
            this.responseTimeout = responseTimeout;
        }

        public String getResponseFailed() {
            return responseFailed;
        }

        public void setResponseFailed(String responseFailed) {
            this.responseFailed = responseFailed;
        }

        public String getMaxSteps() {
            return maxSteps;
        }

        public void setMaxSteps(String maxSteps) {
            this.maxSteps = maxSteps;
        }

        public String getUnknownTool() {
            return unknownTool;
        }

        public void setUnknownTool(String unknownTool) {
            this.unknownTool = unknownTool;
        }

        public String getToolFailed() {
            return toolFailed;
        }

        public void setToolFailed(String toolFailed) {
            this.toolFailed = toolFailed;
        }

        public String getToolInterrupted() {
            return toolInterrupted;
        }

        public void setToolInterrupted(String toolInterrupted) {
            this.toolInterrupted = toolInterrupted;
        }

        public String getNoResult() {
            return noResult;
        }

        public void setNoResult(String noResult) {
            this.noResult = noResult;
        }
    }

    public static class BangumiConfig {
        private String description = "";
        private String keywordParam = "";
        private String missingKeyword = "";
        private String noSubjectResult = "";
        private String noAnimeResult = "";
        private String seriesResultTemplate = "";
        private String seasonQuestionRegex = "";
        private String shortSeriesRegex = "";
        private String shortSeriesSuffixRegex = "";
        private String titleStopRegex = "";
        private String titlePrefixRegex = "";
        private String titleSuffixRegex = "";
        private List<String> searchKeywords = new ArrayList<>();
        private String resultTemplate = "";
        private String displayNameTemplate = "";
        private String itemPrefix = "";
        private String typeLabel = "";
        private String scoreLabel = "";
        private String rankLabel = "";
        private String episodesLabel = "";
        private String airDateLabel = "";
        private String summaryLabel = "";
        private String truncatedSuffix = "";
        private String unknownType = "";
        private String bookType = "";
        private String animeType = "";
        private String musicType = "";
        private String gameType = "";
        private String realType = "";
        private String fallbackTypePrefix = "";

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getKeywordParam() {
            return keywordParam;
        }

        public void setKeywordParam(String keywordParam) {
            this.keywordParam = keywordParam;
        }

        public String getMissingKeyword() {
            return missingKeyword;
        }

        public void setMissingKeyword(String missingKeyword) {
            this.missingKeyword = missingKeyword;
        }

        public String getNoSubjectResult() {
            return noSubjectResult;
        }

        public void setNoSubjectResult(String noSubjectResult) {
            this.noSubjectResult = noSubjectResult;
        }

        public String getNoAnimeResult() {
            return noAnimeResult;
        }

        public void setNoAnimeResult(String noAnimeResult) {
            this.noAnimeResult = noAnimeResult;
        }

        public String getSeriesResultTemplate() {
            return seriesResultTemplate;
        }

        public void setSeriesResultTemplate(String seriesResultTemplate) {
            this.seriesResultTemplate = seriesResultTemplate;
        }

        public String getSeasonQuestionRegex() {
            return seasonQuestionRegex;
        }

        public void setSeasonQuestionRegex(String seasonQuestionRegex) {
            this.seasonQuestionRegex = seasonQuestionRegex;
        }

        public String getShortSeriesRegex() {
            return shortSeriesRegex;
        }

        public void setShortSeriesRegex(String shortSeriesRegex) {
            this.shortSeriesRegex = shortSeriesRegex;
        }

        public String getShortSeriesSuffixRegex() {
            return shortSeriesSuffixRegex;
        }

        public void setShortSeriesSuffixRegex(String shortSeriesSuffixRegex) {
            this.shortSeriesSuffixRegex = shortSeriesSuffixRegex;
        }

        public String getTitleStopRegex() {
            return titleStopRegex;
        }

        public void setTitleStopRegex(String titleStopRegex) {
            this.titleStopRegex = titleStopRegex;
        }

        public String getTitlePrefixRegex() {
            return titlePrefixRegex;
        }

        public void setTitlePrefixRegex(String titlePrefixRegex) {
            this.titlePrefixRegex = titlePrefixRegex;
        }

        public String getTitleSuffixRegex() {
            return titleSuffixRegex;
        }

        public void setTitleSuffixRegex(String titleSuffixRegex) {
            this.titleSuffixRegex = titleSuffixRegex;
        }

        public List<String> getSearchKeywords() {
            return searchKeywords;
        }

        public void setSearchKeywords(List<String> searchKeywords) {
            this.searchKeywords = searchKeywords;
        }

        public String getResultTemplate() {
            return resultTemplate;
        }

        public void setResultTemplate(String resultTemplate) {
            this.resultTemplate = resultTemplate;
        }

        public String getDisplayNameTemplate() {
            return displayNameTemplate;
        }

        public void setDisplayNameTemplate(String displayNameTemplate) {
            this.displayNameTemplate = displayNameTemplate;
        }

        public String getItemPrefix() {
            return itemPrefix;
        }

        public void setItemPrefix(String itemPrefix) {
            this.itemPrefix = itemPrefix;
        }

        public String getTypeLabel() {
            return typeLabel;
        }

        public void setTypeLabel(String typeLabel) {
            this.typeLabel = typeLabel;
        }

        public String getScoreLabel() {
            return scoreLabel;
        }

        public void setScoreLabel(String scoreLabel) {
            this.scoreLabel = scoreLabel;
        }

        public String getRankLabel() {
            return rankLabel;
        }

        public void setRankLabel(String rankLabel) {
            this.rankLabel = rankLabel;
        }

        public String getEpisodesLabel() {
            return episodesLabel;
        }

        public void setEpisodesLabel(String episodesLabel) {
            this.episodesLabel = episodesLabel;
        }

        public String getAirDateLabel() {
            return airDateLabel;
        }

        public void setAirDateLabel(String airDateLabel) {
            this.airDateLabel = airDateLabel;
        }

        public String getSummaryLabel() {
            return summaryLabel;
        }

        public void setSummaryLabel(String summaryLabel) {
            this.summaryLabel = summaryLabel;
        }

        public String getTruncatedSuffix() {
            return truncatedSuffix;
        }

        public void setTruncatedSuffix(String truncatedSuffix) {
            this.truncatedSuffix = truncatedSuffix;
        }

        public String getUnknownType() {
            return unknownType;
        }

        public void setUnknownType(String unknownType) {
            this.unknownType = unknownType;
        }

        public String getBookType() {
            return bookType;
        }

        public void setBookType(String bookType) {
            this.bookType = bookType;
        }

        public String getAnimeType() {
            return animeType;
        }

        public void setAnimeType(String animeType) {
            this.animeType = animeType;
        }

        public String getMusicType() {
            return musicType;
        }

        public void setMusicType(String musicType) {
            this.musicType = musicType;
        }

        public String getGameType() {
            return gameType;
        }

        public void setGameType(String gameType) {
            this.gameType = gameType;
        }

        public String getRealType() {
            return realType;
        }

        public void setRealType(String realType) {
            this.realType = realType;
        }

        public String getFallbackTypePrefix() {
            return fallbackTypePrefix;
        }

        public void setFallbackTypePrefix(String fallbackTypePrefix) {
            this.fallbackTypePrefix = fallbackTypePrefix;
        }
    }

    public static class ContextLabels {
        private String timeLabel = "";
        private String userLabel = "";
        private String pageLabel = "";
        private String assistantLabel = "";
        private String observationLabel = "";
        private String currentQuestionHeading = "";
        private String quotedTitleRegex = "";
        private String titleStopRegex = "";
        private String topicPrefixRegex = "";
        private String topicSuffixRegex = "";
        private String clauseSplitRegex = "";
        private String commaMarker = "";

        public String getTimeLabel() {
            return timeLabel;
        }

        public void setTimeLabel(String timeLabel) {
            this.timeLabel = timeLabel;
        }

        public String getUserLabel() {
            return userLabel;
        }

        public void setUserLabel(String userLabel) {
            this.userLabel = userLabel;
        }

        public String getPageLabel() {
            return pageLabel;
        }

        public void setPageLabel(String pageLabel) {
            this.pageLabel = pageLabel;
        }

        public String getAssistantLabel() {
            return assistantLabel;
        }

        public void setAssistantLabel(String assistantLabel) {
            this.assistantLabel = assistantLabel;
        }

        public String getObservationLabel() {
            return observationLabel;
        }

        public void setObservationLabel(String observationLabel) {
            this.observationLabel = observationLabel;
        }

        public String getCurrentQuestionHeading() {
            return currentQuestionHeading;
        }

        public void setCurrentQuestionHeading(String currentQuestionHeading) {
            this.currentQuestionHeading = currentQuestionHeading;
        }

        public String getQuotedTitleRegex() {
            return quotedTitleRegex;
        }

        public void setQuotedTitleRegex(String quotedTitleRegex) {
            this.quotedTitleRegex = quotedTitleRegex;
        }

        public String getTitleStopRegex() {
            return titleStopRegex;
        }

        public void setTitleStopRegex(String titleStopRegex) {
            this.titleStopRegex = titleStopRegex;
        }

        public String getTopicPrefixRegex() {
            return topicPrefixRegex;
        }

        public void setTopicPrefixRegex(String topicPrefixRegex) {
            this.topicPrefixRegex = topicPrefixRegex;
        }

        public String getTopicSuffixRegex() {
            return topicSuffixRegex;
        }

        public void setTopicSuffixRegex(String topicSuffixRegex) {
            this.topicSuffixRegex = topicSuffixRegex;
        }

        public String getClauseSplitRegex() {
            return clauseSplitRegex;
        }

        public void setClauseSplitRegex(String clauseSplitRegex) {
            this.clauseSplitRegex = clauseSplitRegex;
        }

        public String getCommaMarker() {
            return commaMarker;
        }

        public void setCommaMarker(String commaMarker) {
            this.commaMarker = commaMarker;
        }
    }
}
