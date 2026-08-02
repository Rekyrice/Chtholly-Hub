package com.chtholly.agent.tools;

import com.chtholly.agent.AgentTool;
import com.chtholly.agent.ParamDef;
import com.chtholly.agent.observability.AgentToolDiagnostics;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.search.service.SearchService;
import com.chtholly.search.service.SearchSort;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 站内 ES 全文搜索。 */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
@RequiredArgsConstructor
public class FulltextSearchTool implements AgentTool {

    private static final int MAX_DIAGNOSTIC_OBSERVATION_CHARS = 65_536;
    private static final Pattern RESULT_ITEM = Pattern.compile(
            "(?m)^- 《[^\\r\\n]{0,500}》 \\(/post/([^\\s()\\r\\n]{1,300})\\)[ \\t]*$");

    private final SearchService searchService;

    @Override
    public String name() {
        return "fulltext_search";
    }

    @Override
    public String description() {
        return "搜索 Chtholly Hub 站内已发布帖子（仅博客，不含 Bangumi 动漫库）。";
    }

    @Override
    public Map<String, ParamDef> parameterSchema() {
        return Map.of(
                "q", ParamDef.string("搜索关键词", true, 1, 120)
        );
    }

    @Override
    public AgentToolDiagnostics traceDiagnostics(Map<String, Object> input, String observation) {
        AgentToolDiagnostics base = AgentTool.super.traceDiagnostics(input, observation);
        String bounded = boundedObservation(observation);
        Matcher matcher = RESULT_ITEM.matcher(bounded);
        List<String> selectedIds = new ArrayList<>();
        int resultCount = 0;
        while (matcher.find()) {
            resultCount++;
            selectedIds.add("/post/" + matcher.group(1));
        }
        Integer recognizedCount = resultCount > 0
                ? Integer.valueOf(resultCount)
                : bounded.stripLeading().startsWith("未找到") ? Integer.valueOf(0) : null;
        return diagnostics(base, recognizedCount, selectedIds);
    }

    @Override
    public String execute(Map<String, Object> input, long userId) {
        String q = str(input.get("q"));
        if (q == null || q.isBlank()) {
            return "错误：缺少参数 q（搜索关键词）";
        }
        PageResponse<FeedItemResponse> res = searchService.search(
                q.trim(), 5, null, null, SearchSort.RELEVANCE, userId);
        if (res.items().isEmpty()) {
            return "未找到与「" + q + "」相关的帖子。";
        }
        return res.items().stream()
                .map(this::formatItem)
                .collect(Collectors.joining("\n\n"));
    }

    private String formatItem(FeedItemResponse item) {
        String desc = item.description() == null ? "" : item.description().strip();
        if (desc.length() > 200) {
            desc = desc.substring(0, 200) + "…";
        }
        return "- 《" + nullToEmpty(item.title()) + "》"
                + (item.slug() != null ? " (/post/" + item.slug() + ")" : "")
                + (desc.isEmpty() ? "" : "\n  摘要：" + desc);
    }

    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
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
                "published_post_search",
                "chtholly_search",
                "elasticsearch_or_degraded",
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
