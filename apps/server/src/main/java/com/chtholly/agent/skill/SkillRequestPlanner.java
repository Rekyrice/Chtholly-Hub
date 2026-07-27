package com.chtholly.agent.skill;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates one selected Skill request and derives its evidence policy and retrieval query. */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class SkillRequestPlanner {

    private static final Pattern TITLE = Pattern.compile("(?m)^标题[：:]\\s*(.+)$");
    private static final Pattern POST_SLUG = Pattern.compile("(?m)^postSlug[：:]\\s*(.+)$");
    private static final Pattern SOURCE = Pattern.compile("(?m)^来源[：:]\\s*(.+)$");
    private static final Pattern PAGE = Pattern.compile("(?m)^页面[：:]\\s*(.+)$");
    private static final Pattern OUTLINE_COMMAND = Pattern.compile(
            "^(?:请|帮我|麻烦你|给我)*\\s*(?:生成|写|做|列(?:出)?)\\s*(?:一份|一个)?\\s*");
    private static final Pattern OUTLINE_SUFFIX = Pattern.compile(
            "(?:的)?\\s*(?:技术分享)?\\s*(?:文章|写作)?\\s*(?:大纲|提纲|结构)\\s*$");
    private static final Pattern EXPLAIN_COMMAND = Pattern.compile(
            "^(?:请|帮我|麻烦你)?\\s*(?:解释(?:一下)?|说明(?:一下)?|讲讲)\\s*");
    private static final Pattern FACT_CHECK_COMMAND = Pattern.compile(
            "^(?:请|帮我|麻烦你)?\\s*(?:做)?\\s*(?:事实核查|核查|查证)\\s*");
    private static final Pattern EDGE_PUNCTUATION = Pattern.compile(
            "^[\\s，,。.!！?？:：;；“”\"'《》]+|[\\s，,。.!！?？:：;；“”\"'《》]+$");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final String[] SITE_CONSTRAINTS = {
            "根据站内资料", "结合站内资料", "引用站内内容", "根据当前页面",
            "结合当前页面", "结合这篇文章", "根据这篇文章", "引用这篇文章"
    };

    /**
     * Plans one selected Skill without executing retrieval or a model call.
     *
     * @param definition selected Skill definition
     * @param question user request
     * @param pageContext server-formatted page context
     * @return immutable per-turn plan
     */
    public SkillRequestPlan plan(
            SkillDefinition definition,
            String question,
            String pageContext) {
        String skillId = definition == null ? "" : definition.id();
        String normalizedQuestion = normalize(question);
        String normalizedPageContext = pageContext == null ? "" : pageContext.strip();
        return switch (skillId) {
            case "page-explain" -> planPageExplain(normalizedQuestion, normalizedPageContext);
            case "evidence-outline" -> planOutline(normalizedQuestion, normalizedPageContext);
            case "draft-fact-check" -> planFactCheck(normalizedQuestion);
            default -> ready(
                    definition == null ? EvidencePolicy.NOT_NEEDED : definition.defaultEvidencePolicy(),
                    normalizedQuestion,
                    "skill_default");
        };
    }

    private SkillRequestPlan planPageExplain(String question, String pageContext) {
        String target = cleanExplainTarget(question);
        String pageQuery = pageQuery(pageContext);
        if (target.isBlank() && pageQuery.isBlank()) {
            return clarification("explain_target_missing");
        }
        if (!pageQuery.isBlank()) {
            return ready(EvidencePolicy.REQUIRED, pageQuery, "current_page");
        }
        EvidencePolicy policy = hasSiteConstraint(question)
                ? EvidencePolicy.REQUIRED
                : EvidencePolicy.OPTIONAL;
        return ready(policy, target, "explicit_explain_target");
    }

    private SkillRequestPlan planOutline(String question, String pageContext) {
        String topic = cleanOutlineTopic(question);
        String pageQuery = pageQuery(pageContext);
        if (topic.isBlank() && pageQuery.isBlank()) {
            return clarification("outline_topic_missing");
        }
        boolean grounded = hasSiteConstraint(question) || !pageQuery.isBlank();
        String query = topic.isBlank() ? pageQuery : topic;
        return ready(
                grounded ? EvidencePolicy.REQUIRED : EvidencePolicy.OPTIONAL,
                query,
                grounded ? "grounded_outline" : "general_outline");
    }

    private SkillRequestPlan planFactCheck(String question) {
        String draft = cleanFactCheckSubject(question);
        if (draft.isBlank()) {
            return clarification("fact_check_draft_missing");
        }
        return ready(EvidencePolicy.REQUIRED, draft, "fact_check_claims");
    }

    static String cleanOutlineTopic(String question) {
        String text = removeSiteConstraints(normalize(question));
        text = OUTLINE_COMMAND.matcher(text).replaceFirst("");
        text = OUTLINE_SUFFIX.matcher(text).replaceFirst("");
        text = stripEdges(text);
        if (text.startsWith("关于")) {
            text = stripEdges(text.substring("关于".length()));
        }
        if (text.endsWith("的")) {
            text = stripEdges(text.substring(0, text.length() - 1));
        }
        return normalize(text);
    }

    static String cleanExplainTarget(String question) {
        String text = removeSiteConstraints(normalize(question));
        text = EXPLAIN_COMMAND.matcher(text).replaceFirst("");
        text = stripEdges(text);
        if (text.equals("这篇文章") || text.equals("这个页面") || text.equals("当前页面")
                || text.equals("一下")) {
            return "";
        }
        return normalize(text);
    }

    static String cleanFactCheckSubject(String question) {
        String text = FACT_CHECK_COMMAND.matcher(normalize(question)).replaceFirst("");
        return normalize(stripEdges(text));
    }

    private static String pageQuery(String pageContext) {
        if (pageContext == null || pageContext.isBlank()) {
            return "";
        }
        String title = group(TITLE.matcher(pageContext));
        String slug = group(POST_SLUG.matcher(pageContext));
        String source = group(SOURCE.matcher(pageContext));
        String page = group(PAGE.matcher(pageContext));
        if (slug.isBlank() && source.startsWith("post:")) {
            slug = stripEdges(source.substring("post:".length()));
        }
        if (isGenericAgentShell(page) && slug.isBlank()) {
            return "";
        }
        if (!title.isBlank() && !slug.isBlank()) {
            return normalize(title + " " + slug);
        }
        if (!slug.isBlank()) {
            return normalize(slug);
        }
        if (!title.isBlank()) {
            return normalize(title);
        }
        return page.isBlank() ? normalize(pageContext) : normalize(page);
    }

    private static boolean isGenericAgentShell(String page) {
        String normalized = page == null ? "" : page.strip().toLowerCase(Locale.ROOT);
        return normalized.matches("^/(?:agent|chtholly)(?:[/?#].*)?$");
    }

    private static String group(Matcher matcher) {
        return matcher.find() ? stripEdges(matcher.group(1)) : "";
    }

    private static boolean hasSiteConstraint(String question) {
        String text = question == null ? "" : question.toLowerCase(Locale.ROOT);
        for (String constraint : SITE_CONSTRAINTS) {
            if (text.contains(constraint.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String removeSiteConstraints(String input) {
        String result = input;
        for (String constraint : SITE_CONSTRAINTS) {
            result = result.replace(constraint, " ");
        }
        return normalize(stripEdges(result));
    }

    private static String normalize(String input) {
        if (input == null) {
            return "";
        }
        return WHITESPACE.matcher(input.strip()).replaceAll(" ");
    }

    private static String stripEdges(String input) {
        return EDGE_PUNCTUATION.matcher(input == null ? "" : input).replaceAll("").strip();
    }

    private static SkillRequestPlan ready(
            EvidencePolicy policy,
            String query,
            String reason) {
        return new SkillRequestPlan(PlanStatus.READY, reason, policy, query);
    }

    private static SkillRequestPlan clarification(String reason) {
        return new SkillRequestPlan(
                PlanStatus.NEEDS_CLARIFICATION,
                reason,
                EvidencePolicy.NOT_NEEDED,
                "");
    }

    public enum PlanStatus {
        READY,
        NEEDS_CLARIFICATION
    }

    public record SkillRequestPlan(
            PlanStatus status,
            String reason,
            EvidencePolicy evidencePolicy,
            String retrievalQuery) {

        public SkillRequestPlan {
            reason = reason == null ? "" : reason.strip();
            evidencePolicy = evidencePolicy == null ? EvidencePolicy.NOT_NEEDED : evidencePolicy;
            retrievalQuery = retrievalQuery == null ? "" : retrievalQuery.strip();
        }
    }
}
