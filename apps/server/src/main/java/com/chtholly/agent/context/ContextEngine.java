package com.chtholly.agent.context;

import com.chtholly.agent.AgentTool;
import com.chtholly.agent.CharacterSoulService;
import com.chtholly.agent.ParamDef;
import com.chtholly.agent.learning.InsightService;
import com.chtholly.agent.memory.AgentMemoryStore;
import com.chtholly.agent.state.CharacterState;
import com.chtholly.agent.state.CharacterStateService;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * Dynamically assembles the system prompt based on multiple context layers.
 *
 * <p>Priority order (highest to lowest):
 * 1. Character Soul (identity)
 * 2. Character State (mood, relationship)
 * 3. Page context (what the user is viewing)
 * 4. User profile (who the user is)
 * 5. Long-term memory (relevant past interactions)
 * 6. Active insights (learned behavior rules)
 * 7. Tools (available actions)
 * 8. Conversation history (recent turns)
 * 9. Current question
 */
@Service
public class ContextEngine {

    private final CharacterSoulService soulService;
    private final CharacterStateService stateService;
    @SuppressWarnings("unused")
    private final AgentMemoryStore memoryStore;
    private final InsightService insightService;

    public ContextEngine(CharacterSoulService soulService,
                         CharacterStateService stateService,
                         AgentMemoryStore memoryStore,
                         InsightService insightService) {
        this.soulService = soulService;
        this.stateService = stateService;
        this.memoryStore = memoryStore;
        this.insightService = insightService;
    }

    /**
     * Builds the complete system prompt with identity, state, page context,
     * insights, and the current question.
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
        StringBuilder sb = new StringBuilder();

        appendSoul(sb);
        appendCharacterState(sb, userId);
        appendPageContext(sb, pageContext);
        appendInsights(sb, userId);
        appendTools(sb, tools);
        appendConversationHistory(sb, conversationHistory);
        appendCurrentQuestion(sb, userQuestion);

        return sb.toString();
    }

    private void appendSoul(StringBuilder sb) {
        sb.append("## 你的身份\n\n");
        sb.append(soulService.getSoulContent());
        sb.append("\n\n");
    }

    private void appendCharacterState(StringBuilder sb, long userId) {
        CharacterState state = stateService.load(userId);
        double moodBaseline = stateService.getMoodBaseline();
        double intimacy = state.relationship().intimacy();

        sb.append("## 当前状态\n\n");
        sb.append("你和这位用户的亲密度：")
                .append(formatIntimacy(intimacy))
                .append("（")
                .append(intimacyLabel(intimacy))
                .append("）\n");
        sb.append("你当前的心境：").append(formatMood(state.mood().valence())).append('\n');
        sb.append("当前时间段：")
                .append(timePeriodLabel())
                .append("（心境基线：")
                .append(moodBaseline)
                .append("）\n");
        sb.append("互动次数：").append(state.relationship().interactionCount()).append("\n\n");
    }

    private void appendPageContext(StringBuilder sb, String pageContext) {
        if (pageContext == null || pageContext.isBlank()) {
            return;
        }
        sb.append("## 用户当前在看\n\n");
        sb.append(pageContext.trim()).append("\n\n");
    }

    private void appendInsights(StringBuilder sb, long userId) {
        List<String> insights = insightService.getActiveInsights(userId, 5);
        if (insights == null || insights.isEmpty()) {
            return;
        }
        sb.append("## 你学到的行为规则\n\n");
        for (String insight : insights) {
            if (insight != null && !insight.isBlank()) {
                sb.append("- ").append(insight.trim()).append('\n');
            }
        }
        sb.append('\n');
    }

    private void appendTools(StringBuilder sb, Iterable<AgentTool> tools) {
        sb.append("## 可用工具\n\n");
        for (AgentTool tool : tools) {
            sb.append("### ").append(tool.name()).append('\n');
            sb.append(tool.description());
            appendParameterSchema(sb, tool.parameterSchema());
            sb.append("\n\n");
        }

        sb.append("## 工具使用准则\n\n");
        sb.append("1. 优先用工具获取事实，不确定时查一下再回答\n");
        sb.append("2. 每次只调用一个工具，等结果返回后再决定下一步\n");
        sb.append("3. 如果站内搜索无结果，尝试 Bangumi 工具搜索动漫相关内容\n");
        sb.append("4. 不要编造工具返回的数据，如实告诉用户查询结果\n\n");
        sb.append("输出格式：只输出单个 JSON 对象；调用工具用 {\"action\":\"工具名\",\"input\":{...}}，可以回答时用 {\"action\":\"final\",\"answer\":\"占位\"}\n\n");
    }

    private void appendConversationHistory(StringBuilder sb, String conversationHistory) {
        sb.append("## 对话历史\n\n");
        if (conversationHistory == null || conversationHistory.isBlank()) {
            sb.append("（暂无）");
        } else {
            sb.append(conversationHistory.trim());
        }
        sb.append("\n\n");
    }

    private void appendCurrentQuestion(StringBuilder sb, String userQuestion) {
        sb.append("## 用户的问题\n\n");
        sb.append(userQuestion == null ? "" : userQuestion.trim());
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
