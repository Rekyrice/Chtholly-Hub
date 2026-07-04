package com.chtholly.agent.context;

import com.chtholly.agent.AgentTool;
import com.chtholly.agent.ParamDef;
import com.chtholly.agent.anchor.AnchorContext;
import com.chtholly.agent.anchor.AnchorManager;
import com.chtholly.agent.anchor.KnowledgeService;
import com.chtholly.agent.content.ContentAnalysis;
import com.chtholly.agent.content.ContentUnderstandingService;
import com.chtholly.agent.content.Entity;
import com.chtholly.agent.memory.AgentTurn;
import com.chtholly.agent.search.HybridSearchService;
import com.chtholly.agent.search.SearchResult;
import com.chtholly.agent.state.CharacterState;
import com.chtholly.agent.state.CharacterStateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
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

    public ContextEngine(AnchorManager anchorManager, CharacterStateService stateService,
                         ObjectProvider<HybridSearchService> hybridSearchServiceProvider,
                         ObjectProvider<KnowledgeService> knowledgeServiceProvider,
                         ObjectProvider<ContentUnderstandingService> contentUnderstandingServiceProvider) {
        this(anchorManager,
                stateService,
                hybridSearchServiceProvider.getIfAvailable(),
                knowledgeServiceProvider.getIfAvailable(),
                contentUnderstandingServiceProvider.getIfAvailable());
    }

    ContextEngine(AnchorManager anchorManager, CharacterStateService stateService) {
        this(anchorManager, stateService, (HybridSearchService) null, (KnowledgeService) null, null);
    }

    ContextEngine(AnchorManager anchorManager, CharacterStateService stateService,
                  HybridSearchService hybridSearchService) {
        this(anchorManager, stateService, hybridSearchService, null, null);
    }

    ContextEngine(AnchorManager anchorManager, CharacterStateService stateService,
                  HybridSearchService hybridSearchService, KnowledgeService knowledgeService) {
        this(anchorManager, stateService, hybridSearchService, knowledgeService, null);
    }

    ContextEngine(AnchorManager anchorManager, CharacterStateService stateService,
                  HybridSearchService hybridSearchService, KnowledgeService knowledgeService,
                  ContentUnderstandingService contentUnderstandingService) {
        this.anchorManager = anchorManager;
        this.stateService = stateService;
        this.hybridSearchService = hybridSearchService;
        this.knowledgeService = knowledgeService;
        this.contentUnderstandingService = contentUnderstandingService;
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
        appendPageContext(sb, pageContext);
        appendCurrentPostAnalysis(sb, pageContext);
        appendKnownFacts(sb, userQuestion);
        appendRelevantKnowledge(sb, anchors.semantic(), userQuestion);
        appendProceduralRules(sb, anchors.procedural());
        appendTools(sb, tools);
        appendConversationHistory(sb, conversationHistory, anchors.episodic());
        appendCurrentQuestion(sb, userQuestion);

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
        sb.append("互动次数：").append(safeState.relationship().interactionCount()).append("\n\n");
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

    private void appendTools(StringBuilder sb, Iterable<AgentTool> tools) {
        sb.append("## 可用工具\n\n");
        if (tools != null) {
            for (AgentTool tool : tools) {
                sb.append("### ").append(tool.name()).append('\n');
                sb.append(tool.description());
                appendParameterSchema(sb, tool.parameterSchema());
                sb.append("\n\n");
            }
        }

        sb.append("## 工具使用准则\n\n");
        sb.append("1. 优先用工具获取事实，不确定时查一下再回答\n");
        sb.append("2. 每次只调用一个工具，等结果返回后再决定下一步\n");
        sb.append("3. 如果站内搜索无结果，尝试 Bangumi 工具搜索动漫相关内容\n");
        sb.append("4. 不要编造工具返回的数据，如实告诉用户查询结果\n\n");
        sb.append("输出格式：只输出单个 JSON 对象；调用工具用 {\"action\":\"工具名\",\"input\":{...}}，可以回答时用 {\"action\":\"final\",\"answer\":\"占位\"}\n\n");
    }

    private void appendConversationHistory(StringBuilder sb, String conversationHistory, List<AgentTurn> episodic) {
        sb.append("## 对话历史\n\n");
        if (conversationHistory != null && !conversationHistory.isBlank()) {
            sb.append(conversationHistory.trim());
        } else if (episodic != null && !episodic.isEmpty()) {
            sb.append(formatEpisodicTurns(episodic));
        } else {
            sb.append("（暂无）");
        }
        sb.append("\n\n");
    }

    private void appendCurrentQuestion(StringBuilder sb, String userQuestion) {
        sb.append("## 用户的问题\n\n");
        sb.append(userQuestion == null ? "" : userQuestion.trim());
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

    private static String formatEpisodicTurns(List<AgentTurn> turns) {
        StringBuilder sb = new StringBuilder();
        for (AgentTurn turn : turns) {
            if (turn == null || turn.content() == null || turn.content().isBlank()) {
                continue;
            }
            sb.append(turn.role() == AgentTurn.Role.USER ? "User: " : "Assistant: ")
                    .append(turn.content().trim())
                    .append('\n');
        }
        return sb.toString().trim();
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

    private void appendParameterSchema(StringBuilder sb, Map<String, ParamDef> schema) {
        if (schema == null || schema.isEmpty()) {
            return;
        }
        sb.append("\n  参数：");
        for (Map.Entry<String, ParamDef> entry : schema.entrySet()) {
            ParamDef def = entry.getValue();
            sb.append("\n    - ").append(entry.getKey())
                    .append(" (").append(schemaTypeLabel(def.type()))
                    .append(def.required() ? ", 必填" : ", 可选")
                    .append("): ")
                    .append(def.description());
        }
    }

    private static String schemaTypeLabel(Class<?> type) {
        if (type == String.class) {
            return "string";
        }
        if (type == Integer.class || type == int.class) {
            return "integer";
        }
        if (type == Boolean.class || type == boolean.class) {
            return "boolean";
        }
        return type.getSimpleName();
    }
}
