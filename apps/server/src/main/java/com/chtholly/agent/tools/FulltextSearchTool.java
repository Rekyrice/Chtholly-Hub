package com.chtholly.agent.tools;

import com.chtholly.agent.AgentTool;
import com.chtholly.agent.ParamDef;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.search.service.SearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.stream.Collectors;

/** 站内 ES 全文搜索。 */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
@RequiredArgsConstructor
public class FulltextSearchTool implements AgentTool {

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
                "q", new ParamDef("搜索关键词", String.class, true)
        );
    }

    @Override
    public String execute(Map<String, Object> input, long userId) {
        String q = str(input.get("q"));
        if (q == null || q.isBlank()) {
            return "错误：缺少参数 q（搜索关键词）";
        }
        PageResponse<FeedItemResponse> res = searchService.search(q.trim(), 5, null, null, userId);
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
}
