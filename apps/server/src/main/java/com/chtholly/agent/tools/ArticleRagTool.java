package com.chtholly.agent.tools;

import com.chtholly.agent.AgentTool;
import com.chtholly.agent.ParamDef;
import com.chtholly.agent.observability.AgentToolDiagnostics;
import com.chtholly.agent.search.SearchResult;
import com.chtholly.llm.rag.RagQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only semantic article search through the MySQL-authorized RAG boundary. */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
@RequiredArgsConstructor
public class ArticleRagTool implements AgentTool {

    private static final int MAX_DIAGNOSTIC_OBSERVATION_CHARS = 65_536;
    private static final Pattern RESULT_HEADER = Pattern.compile(
            "(?m)^《[^\\r\\n]{0,500}》 \\(([^()\\r\\n]{1,128})\\)[ \\t]*$");

    private final RagQueryService ragQueryService;

    @Override
    public String name() {
        return "article_rag";
    }

    @Override
    public String description() {
        return "按语义检索当前公开且内容指纹有效的站内帖子片段。"
                + " input: {\"query\":\"问题或关键词\",\"topK\":5}";
    }

    @Override
    public Map<String, ParamDef> parameterSchema() {
        return Map.of(
                "query", ParamDef.string("问题或检索关键词", true, 1, 200),
                "topK", ParamDef.integer("返回片段数量", false, 1, 10));
    }

    @Override
    public AgentToolDiagnostics traceDiagnostics(Map<String, Object> input, String observation) {
        AgentToolDiagnostics base = AgentTool.super.traceDiagnostics(input, observation);
        String bounded = boundedObservation(observation);
        Matcher matcher = RESULT_HEADER.matcher(bounded);
        List<String> selectedIds = new ArrayList<>();
        int resultCount = 0;
        while (matcher.find()) {
            resultCount++;
            selectedIds.add(matcher.group(1));
        }
        Integer recognizedCount = resultCount > 0
                ? Integer.valueOf(resultCount)
                : bounded.stripLeading().startsWith("未找到") ? Integer.valueOf(0) : null;
        return diagnostics(base, recognizedCount, selectedIds);
    }

    @Override
    public String execute(Map<String, Object> input, long userId) {
        String query = string(input.get("query"));
        if (query == null || query.isBlank()) {
            return "错误：缺少参数 query";
        }
        int topK = Math.min(Math.max(parseInt(input.get("topK"), 5), 1), 10);
        List<SearchResult> results = ragQueryService.search(query.trim(), topK);
        if (results == null || results.isEmpty()) {
            return "未找到与「" + query + "」相关且当前可公开访问的帖子片段。";
        }

        List<String> blocks = new ArrayList<>(results.size());
        for (SearchResult result : results) {
            if (result == null || result.getId() == null || result.getId().isBlank()) {
                continue;
            }
            String title = result.getTitle() == null || result.getTitle().isBlank()
                    ? "帖子"
                    : result.getTitle();
            blocks.add("《" + title + "》 (" + result.getId() + ")\n"
                    + truncate(result.getSnippet(), 400));
        }
        return blocks.isEmpty()
                ? "未找到与「" + query + "」相关且当前可公开访问的帖子片段。"
                : String.join("\n\n---\n\n", blocks);
    }

    private int parseInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String truncate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxChars ? value : value.substring(0, maxChars) + "…";
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
                "semantic_article_search",
                "chtholly_rag",
                "public_index",
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
