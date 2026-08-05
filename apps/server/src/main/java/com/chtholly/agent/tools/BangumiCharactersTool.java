package com.chtholly.agent.tools;

import com.chtholly.agent.AgentTool;
import com.chtholly.agent.ParamDef;
import com.chtholly.agent.config.AgentDomainConfig;
import com.chtholly.agent.memory.AgentContextUtil;
import com.chtholly.agent.observability.AgentToolDiagnostics;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bangumi 条目角色查询：主要人物、宿舍伙伴等登场角色。 */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
@RequiredArgsConstructor
public class BangumiCharactersTool implements AgentTool {

    private static final int MAX_DIAGNOSTIC_OBSERVATION_CHARS = 65_536;
    private static final Pattern RESULT_COUNT = Pattern.compile(
            "登场角色（共[ \\t]*([0-9]{1,9})[ \\t]*个）");
    private static final Pattern SUBJECT_HEADER = Pattern.compile(
            "(?m)^条目：《[^\\r\\n]{0,500}》\\[Bangumi ([0-9]{1,20})][ \\t]*$");

    private final BangumiService bangumiService;
    private final AgentDomainConfig agentDomainConfig;

    @Override
    public String name() {
        return "bangumi_characters";
    }

    @Override
    public String description() {
        return """
                查询 Bangumi 条目的登场角色列表（主役/配角等）。
                适用于「主要人物有哪些」「宿舍伙伴是谁」等角色类问题；追问时需结合对话历史传入作品 keyword。
                input: {"keyword":"条目名或系列简称"}""";
    }

    @Override
    public Map<String, ParamDef> parameterSchema() {
        return Map.of(
                "keyword", ParamDef.string(
                        "条目名或系列简称；追问时可从对话历史推断", false, 1, 120));
    }

    @Override
    public AgentToolDiagnostics traceDiagnostics(Map<String, Object> input, String observation) {
        AgentToolDiagnostics base = AgentTool.super.traceDiagnostics(input, observation);
        String bounded = boundedObservation(observation);
        Matcher countMatcher = RESULT_COUNT.matcher(bounded);
        Integer resultCount = countMatcher.find() ? Integer.valueOf(countMatcher.group(1)) : null;
        List<String> selectedIds = new ArrayList<>();
        if (resultCount != null) {
            Matcher subjectMatcher = SUBJECT_HEADER.matcher(bounded);
            if (subjectMatcher.find()) {
                selectedIds.add(subjectMatcher.group(1));
            }
        }
        return diagnostics(base, resultCount, selectedIds);
    }

    @Override
    public String execute(Map<String, Object> input, long userId) {
        List<String> keywords = buildKeywordCandidates(input);
        if (keywords.isEmpty()) {
            return "错误：缺少参数 keyword（可从对话历史中的作品名推断）";
        }

        IllegalStateException lastApiError = null;
        for (String keyword : keywords) {
            try {
                String result = bangumiService.describeSubjectCharacters(keyword);
                if (result != null && !result.contains("未找到") && !result.contains("暂无")) {
                    return result;
                }
            } catch (IllegalStateException e) {
                lastApiError = e;
            }
        }

        if (lastApiError != null) {
            throw BangumiToolFailures.unavailable(lastApiError);
        }
        return "Bangumi 未找到与「" + keywords.get(0) + "」相关的角色信息。";
    }

    private List<String> buildKeywordCandidates(Map<String, Object> input) {
        Set<String> candidates = new LinkedHashSet<>();
        String keyword = input.get("keyword") == null ? "" : String.valueOf(input.get("keyword")).trim();
        if (StringUtils.hasText(keyword)) {
            candidates.add(keyword);
        }
        Object userQuestion = input.get("_userQuestion");
        Object history = input.get("_conversationHistory");
        String q = userQuestion == null ? "" : String.valueOf(userQuestion);
        String h = history == null ? "" : String.valueOf(history);
        for (String title : AgentContextUtil.extractWorkTitleCandidates(h, q, agentDomainConfig)) {
            candidates.add(title);
        }
        return new ArrayList<>(candidates);
    }

    private String boundedObservation(String observation) {
        if (observation == null) {
            return "";
        }
        return observation.length() <= MAX_DIAGNOSTIC_OBSERVATION_CHARS
                ? observation
                : observation.substring(0, MAX_DIAGNOSTIC_OBSERVATION_CHARS);
    }

    private AgentToolDiagnostics diagnostics(
            AgentToolDiagnostics base,
            Integer resultCount,
            List<String> selectedIds) {
        return new AgentToolDiagnostics(
                "subject_characters",
                "bangumi",
                "local_subject_then_api",
                base.sanitizedInput(),
                base.outputPreview(),
                base.outputSha256(),
                base.outputChars(),
                base.outputTruncated(),
                resultCount,
                selectedIds,
                base.errorCode());
    }
}
