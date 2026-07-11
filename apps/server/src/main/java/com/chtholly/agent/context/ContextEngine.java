package com.chtholly.agent.context;

import com.chtholly.agent.AgentTool;
import com.chtholly.agent.anchor.AnchorContext;
import com.chtholly.agent.anchor.AnchorManager;
import com.chtholly.agent.anchor.KnowledgeService;
import com.chtholly.agent.content.ContentAnalysis;
import com.chtholly.agent.content.ContentUnderstandingService;
import com.chtholly.agent.content.Entity;
import com.chtholly.agent.graph.KnowledgeGraphService;
import com.chtholly.agent.memory.AgentTurn;
import com.chtholly.agent.mood.SeasonService;
import com.chtholly.agent.search.HybridSearchService;
import com.chtholly.agent.search.SearchResult;
import com.chtholly.agent.state.CharacterState;
import com.chtholly.agent.state.CharacterStateService;
import com.chtholly.agent.state.EmotionState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Dynamically assembles the system prompt from identity anchors and runtime context.
 *
 * <p>Priority order:
 * 1. Identity anchor.
 * 2. Relational state.
 * 3. Page context.
 * 4. Semantic knowledge.
 * 5. Procedural rules.
 * 6. Tools.
 * 7. Conversation history.
 * 8. Current question.
 */
@Slf4j
@Service
public class ContextEngine {

    private final AnchorManager anchorManager;
    private final CharacterStateService stateService;
    private final HybridSearchService hybridSearchService;
    private final KnowledgeService knowledgeService;
    private final ContentUnderstandingService contentUnderstandingService;
    private final SeasonService seasonService;
    private final KnowledgeGraphService knowledgeGraphService;
    private final PromptTailRenderer promptTailRenderer = new PromptTailRenderer();

    @Autowired
    public ContextEngine(AnchorManager anchorManager, CharacterStateService stateService,
                         ObjectProvider<HybridSearchService> hybridSearchServiceProvider,
                         ObjectProvider<KnowledgeService> knowledgeServiceProvider,
                         ObjectProvider<ContentUnderstandingService> contentUnderstandingServiceProvider,
                         ObjectProvider<SeasonService> seasonServiceProvider,
                         ObjectProvider<KnowledgeGraphService> knowledgeGraphServiceProvider) {
        this(anchorManager,
                stateService,
                hybridSearchServiceProvider.getIfAvailable(),
                knowledgeServiceProvider.getIfAvailable(),
                contentUnderstandingServiceProvider.getIfAvailable(),
                seasonServiceProvider.getIfAvailable(),
                knowledgeGraphServiceProvider.getIfAvailable());
    }

    ContextEngine(AnchorManager anchorManager, CharacterStateService stateService) {
        this(anchorManager, stateService, (HybridSearchService) null, (KnowledgeService) null, null, null, null);
    }

    ContextEngine(AnchorManager anchorManager, CharacterStateService stateService,
                  HybridSearchService hybridSearchService) {
        this(anchorManager, stateService, hybridSearchService, null, null, null, null);
    }

    ContextEngine(AnchorManager anchorManager, CharacterStateService stateService,
                  HybridSearchService hybridSearchService, KnowledgeService knowledgeService) {
        this(anchorManager, stateService, hybridSearchService, knowledgeService, null, null, null);
    }

    ContextEngine(AnchorManager anchorManager, CharacterStateService stateService,
                  HybridSearchService hybridSearchService, KnowledgeService knowledgeService,
                  ContentUnderstandingService contentUnderstandingService) {
        this(anchorManager, stateService, hybridSearchService, knowledgeService, contentUnderstandingService, null, null);
    }

    ContextEngine(AnchorManager anchorManager, CharacterStateService stateService,
                  HybridSearchService hybridSearchService, KnowledgeService knowledgeService,
                  ContentUnderstandingService contentUnderstandingService, SeasonService seasonService) {
        this(anchorManager, stateService, hybridSearchService, knowledgeService, contentUnderstandingService, seasonService, null);
    }

    ContextEngine(AnchorManager anchorManager, CharacterStateService stateService,
                  HybridSearchService hybridSearchService, KnowledgeService knowledgeService,
                  ContentUnderstandingService contentUnderstandingService, SeasonService seasonService,
                  KnowledgeGraphService knowledgeGraphService) {
        this.anchorManager = anchorManager;
        this.stateService = stateService;
        this.hybridSearchService = hybridSearchService;
        this.knowledgeService = knowledgeService;
        this.contentUnderstandingService = contentUnderstandingService;
        this.seasonService = seasonService;
        this.knowledgeGraphService = knowledgeGraphService;
    }

    /**
     * Builds the complete system prompt with identity, state, page context,
     * anchor-derived rules, and the current question.
     *
     * @param userId       Authenticated user ID.
     * @param sessionId    Conversation session ID.
     * @param pageContext  Current page context sent by the client.
     * @param userQuestion Current user question.
     * @return Complete system prompt.
     */
    public String buildSystemPrompt(long userId, String sessionId, String pageContext, String userQuestion) {
        return buildSystemPrompt(userId, sessionId, pageContext, List.of(), "", userQuestion);
    }

    /**
     * Builds the complete ReAct system prompt with available tools and recent history.
     *
     * @param userId              Authenticated user ID.
     * @param sessionId           Conversation session ID.
     * @param pageContext         Current page context sent by the client.
     * @param tools               Tools available to the agent loop.
     * @param conversationHistory Recent conversation turns.
     * @param userQuestion        Current user question.
     * @return Complete system prompt.
     */
    public String buildSystemPrompt(long userId, String sessionId, String pageContext,
                                    Iterable<AgentTool> tools, String conversationHistory,
                                    String userQuestion) {
        AnchorContext anchors = anchorManager.buildContext(userId, sessionId);
        StringBuilder sb = new StringBuilder();

        appendSoul(sb, anchors.soul());
        appendCharacterState(sb, anchors.relational());
        appendSeasonalFeeling(sb);
        appendPageContext(sb, pageContext);
        appendCurrentPostAnalysis(sb, pageContext);
        appendKnowledgeGraphContext(sb, userQuestion);
        appendKnownFacts(sb, userQuestion);
        appendRelevantKnowledge(sb, anchors.semantic(), userQuestion);
        appendProceduralRules(sb, anchors.procedural());
        promptTailRenderer.append(sb, tools, conversationHistory, anchors.episodic(), userQuestion);

        return sb.toString();
    }

    private void appendSoul(StringBuilder sb, String soul) {
        sb.append("## 你的身份\n\n");
        sb.append(soul == null ? "" : soul.trim());
        sb.append("\n\n");
    }

    private void appendCharacterState(StringBuilder sb, CharacterState state) {
        CharacterState safeState = state == null ? CharacterState.defaultState() : state;
        double moodBaseline = stateService.getMoodBaseline();
        double moodValence = stateService.getMoodValence();
        EmotionState emotion = stateService.getCurrentEmotion();
        double intimacy = safeState.relationship().intimacy();

        sb.append("## 当前状态\n\n");
        sb.append("你和这位用户的亲密度：")
                .append(formatIntimacy(intimacy))
                .append("（")
                .append(intimacyLabel(intimacy))
                .append("）\n");
        sb.append("你当前的心境：").append(formatMood(safeState.mood().valence())).append('\n');
        sb.append("当前时间段：")
                .append(timePeriodLabel())
                .append("（心境基线：")
                .append(moodBaseline)
                .append("）\n");
        sb.append("- 心情：")
                .append(describeMood(moodValence))
                .append("（valence: ")
                .append(String.format(Locale.ROOT, "%.2f", moodValence))
                .append("）\n");
        sb.append("- 情绪：")
                .append(describeEmotion(emotion))
                .append("（")
                .append(emotion.label())
                .append(", intensity: ")
                .append(String.format(Locale.ROOT, "%.2f", emotion.intensity()))
                .append("）\n");
        sb.append("互动次数：").append(safeState.relationship().interactionCount()).append("\n\n");
    }

    private void appendSeasonalFeeling(StringBuilder sb) {
        if (seasonService == null) {
            return;
        }
        try {
            String seasonalPrompt = seasonService.getSeasonalPrompt();
            if (!hasText(seasonalPrompt)) {
                return;
            }
            sb.append("## 季节感受\n\n");
            sb.append(seasonalPrompt.trim()).append("\n\n");
        } catch (Exception e) {
            log.warn("Seasonal context failed: {}", e.getMessage(), e);
        }
    }

    private void appendPageContext(StringBuilder sb, String pageContext) {
        if (pageContext == null || pageContext.isBlank()) {
            return;
        }
        sb.append("## 用户当前在看\n\n");
        sb.append(pageContext.trim()).append("\n\n");
    }

    private void appendCurrentPostAnalysis(StringBuilder sb, String pageContext) {
        if (contentUnderstandingService == null) {
            return;
        }
        try {
            ContentAnalysis analysis = loadCurrentPostAnalysis(pageContext);
            if (analysis == null) {
                return;
            }
            sb.append("## 当前文章\n\n");
            if (hasText(analysis.summary())) {
                sb.append("摘要：").append(analysis.summary().trim()).append('\n');
            }
            String entities = formatEntities(analysis.entities());
            if (hasText(entities)) {
                sb.append("涉及：").append(entities).append('\n');
            }
            sb.append('\n');
        } catch (Exception e) {
            log.warn("Current post analysis context failed: {}", e.getMessage(), e);
        }
    }

    private void appendKnowledgeGraphContext(StringBuilder sb, String userQuestion) {
        if (knowledgeGraphService == null || !hasText(userQuestion)) {
            return;
        }
        try {
            List<String> graphContext = knowledgeGraphService.contextForQuestion(userQuestion, 5);
            if (graphContext == null || graphContext.isEmpty()) {
                return;
            }
            sb.append("## 话题关联\n\n");
            for (String line : graphContext) {
                if (hasText(line)) {
                    sb.append("- ").append(line.trim()).append('\n');
                }
            }
            sb.append('\n');
        } catch (Exception e) {
            log.warn("Knowledge graph context failed: {}", e.getMessage(), e);
        }
    }

    private ContentAnalysis loadCurrentPostAnalysis(String pageContext) {
        Long postId = extractCurrentPostId(pageContext);
        if (postId != null) {
            return contentUnderstandingService.getAnalysis(postId);
        }
        String postSlug = extractCurrentPostSlug(pageContext);
        if (hasText(postSlug)) {
            return contentUnderstandingService.getAnalysisBySlug(postSlug);
        }
        return null;
    }

    private void appendSemanticKnowledge(StringBuilder sb, List<String> semantic) {
        if (semantic == null || semantic.isEmpty()) {
            return;
        }
        sb.append("## 相关知识\n\n");
        for (String item : semantic) {
            if (item != null && !item.isBlank()) {
                sb.append("- ").append(item.trim()).append('\n');
            }
        }
        sb.append('\n');
    }

    private void appendKnownFacts(StringBuilder sb, String userQuestion) {
        if (!isAnimeKnowledgeIntent(userQuestion) || knowledgeService == null) {
            return;
        }
        try {
            List<String> knowledge = knowledgeService.searchRelevantKnowledge(userQuestion, 3);
            if (knowledge == null || knowledge.isEmpty()) {
                return;
            }
            sb.append("## 你知道的事\n\n");
            for (String item : knowledge) {
                if (hasText(item)) {
                    sb.append("- ").append(item.trim()).append('\n');
                }
            }
            sb.append('\n');
        } catch (Exception e) {
            log.warn("Knowledge base context failed: {}", e.getMessage(), e);
        }
    }

    private void appendRelevantKnowledge(StringBuilder sb, List<String> semantic, String userQuestion) {
        List<String> knowledgeLines = new ArrayList<>();
        if (semantic != null) {
            for (String item : semantic) {
                if (hasText(item)) {
                    knowledgeLines.add(item.trim());
                }
            }
        }

        if (isQueryIntent(userQuestion) && hybridSearchService != null) {
            try {
                for (SearchResult result : hybridSearchService.hybridSearch(userQuestion, 5)) {
                    String line = formatSearchResult(result);
                    if (hasText(line)) {
                        knowledgeLines.add(line);
                    }
                }
            } catch (Exception e) {
                log.warn("Hybrid search context failed: {}", e.getMessage(), e);
            }
        }

        if (!knowledgeLines.isEmpty()) {
            appendSemanticKnowledge(sb, knowledgeLines);
        }
    }

    private void appendProceduralRules(StringBuilder sb, List<String> procedural) {
        if (procedural == null || procedural.isEmpty()) {
            return;
        }
        sb.append("## 你学到的行为规则\n\n");
        for (String rule : procedural) {
            if (rule != null && !rule.isBlank()) {
                sb.append("- ").append(rule.trim()).append('\n');
            }
        }
        sb.append('\n');
    }

    private static String formatSearchResult(SearchResult result) {
        if (result == null) {
            return "";
        }
        String title = hasText(result.getTitle()) ? result.getTitle().trim() : "Result";
        String snippet = hasText(result.getSnippet()) ? result.getSnippet().trim() : "";
        return snippet.isEmpty() ? title : title + "：" + snippet;
    }

    static boolean isQueryIntent(String question) {
        if (!hasText(question)) {
            return false;
        }
        String text = question.trim().toLowerCase();
        return text.contains("查")
                || text.contains("搜")
                || text.contains("找")
                || text.contains("推荐")
                || text.contains("介绍")
                || text.contains("是什么")
                || text.contains("是谁")
                || text.contains("哪里")
                || text.contains("什么时候")
                || text.contains("多少")
                || text.contains("评分")
                || text.contains("角色")
                || text.contains("作品")
                || text.contains("资料")
                || text.contains("信息")
                || text.contains("search")
                || text.contains("find")
                || text.contains("what")
                || text.contains("who")
                || text.contains("when")
                || text.contains("where")
                || text.contains("recommend");
    }

    static boolean isAnimeKnowledgeIntent(String question) {
        if (!hasText(question)) {
            return false;
        }
        String text = question.trim().toLowerCase();
        return text.contains("动漫")
                || text.contains("动画")
                || text.contains("番剧")
                || text.contains("角色")
                || text.contains("故事")
                || text.contains("作品")
                || text.contains("主题")
                || text.contains("观后感")
                || text.contains("治愈")
                || text.contains("珂朵莉")
                || text.contains("芙莉莲")
                || text.contains("夏目")
                || text.contains("轻音")
                || text.contains("虫师")
                || text.contains("紫罗兰")
                || text.contains("clannad")
                || text.contains("air")
                || text.contains("aria")
                || text.contains("anime")
                || text.contains("manga");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static Long extractCurrentPostId(String pageContext) {
        if (!hasText(pageContext)) {
            return null;
        }
        java.util.regex.Matcher matcher = currentPostIdMatcher(pageContext, "(?i)postId\\s*[:：=]\\s*(\\d+)");
        if (matcher == null) {
            matcher = currentPostIdMatcher(pageContext, "(?i)post_id\\s*[:：=]\\s*(\\d+)");
        }
        if (matcher == null) {
            return null;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static java.util.regex.Matcher currentPostIdMatcher(String pageContext, String pattern) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(pattern).matcher(pageContext);
        return matcher.find() ? matcher : null;
    }

    private static String extractCurrentPostSlug(String pageContext) {
        if (!hasText(pageContext)) {
            return null;
        }
        java.util.regex.Matcher matcher = currentPostIdMatcher(pageContext, "(?i)postSlug\\s*[:：=]\\s*([^\\s\\n]+)");
        if (matcher != null) {
            return cleanContextSlug(matcher.group(1));
        }
        matcher = currentPostIdMatcher(pageContext, "(?i)source\\s*[:：=]\\s*post:([^\\s\\n]+)");
        if (matcher != null) {
            return cleanContextSlug(matcher.group(1));
        }
        return null;
    }

    private static String cleanContextSlug(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.trim();
        int queryIndex = cleaned.indexOf('?');
        if (queryIndex >= 0) {
            cleaned = cleaned.substring(0, queryIndex);
        }
        return cleaned.isBlank() ? null : cleaned;
    }

    private static String formatEntities(List<Entity> entities) {
        if (entities == null || entities.isEmpty()) {
            return "";
        }
        return entities.stream()
                .filter(entity -> entity != null && hasText(entity.name()))
                .map(entity -> entity.name().trim())
                .distinct()
                .collect(java.util.stream.Collectors.joining("、"));
    }

    private static String timePeriodLabel() {
        return timePeriodLabel(LocalTime.now().getHour());
    }

    static String timePeriodLabel(int hour) {
        if (hour >= 6 && hour < 9) {
            return "早晨";
        }
        if (hour >= 9 && hour < 12) {
            return "上午";
        }
        if (hour >= 12 && hour < 18) {
            return "下午";
        }
        if (hour >= 18 && hour < 21) {
            return "傍晚";
        }
        if (hour >= 21 || hour < 1) {
            return "深夜";
        }
        return "凌晨";
    }

    static String intimacyLabel(double intimacy) {
        if (intimacy < 0.1) {
            return "陌生人";
        }
        if (intimacy < 0.3) {
            return "刚认识";
        }
        if (intimacy < 0.6) {
            return "熟悉的人";
        }
        if (intimacy < 0.9) {
            return "朋友";
        }
        return "很亲近的人";
    }

    private static String formatIntimacy(double intimacy) {
        if (intimacy >= 0.7) {
            return "亲近";
        }
        if (intimacy >= 0.3) {
            return "熟悉";
        }
        if (intimacy > 0.0) {
            return "刚开始熟悉";
        }
        return "初识";
    }

    private static String formatMood(double valence) {
        if (valence >= 0.4) {
            return "轻快";
        }
        if (valence > -0.2) {
            return "平静";
        }
        if (valence > -0.6) {
            return "有点低落";
        }
        return "低落";
    }

    static String describeMood(double valence) {
        if (valence > 0.5) {
            return "心情很好，有点开心";
        }
        if (valence > 0.2) {
            return "平静偏暖，感觉不错";
        }
        if (valence > -0.2) {
            return "平静，安安静静的";
        }
        if (valence > -0.5) {
            return "有点低落，说不上为什么";
        }
        return "心情不太好，想安静一会儿";
    }

    static String describeEmotion(EmotionState emotion) {
        EmotionState safeEmotion = emotion == null
                ? new EmotionState("平静", 0.2, java.time.Instant.EPOCH, "default")
                : emotion;
        return switch (safeEmotion.label()) {
            case "好奇" -> "对当前话题感到好奇，想了解更多";
            case "开心" -> "因为刚才的互动感到开心";
            case "感伤" -> "有些感伤，但愿意继续聊";
            case "认真" -> "在认真思考";
            case "困" -> "有点犯困了";
            default -> "平静地在这里";
        };
    }

}
