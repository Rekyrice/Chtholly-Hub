package com.chtholly.agent.tools;

import com.chtholly.agent.AgentTool;
import com.chtholly.agent.ParamDef;
import com.chtholly.agent.search.SearchResult;
import com.chtholly.llm.rag.RagQueryService;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.PostDetailRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reads bounded excerpts from one exact public post resolved by its authoritative slug. */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
@RequiredArgsConstructor
public class PostReadTool implements AgentTool {

    private static final int DEFAULT_TOP_K = 4;
    private static final int MAX_TOP_K = 6;
    private static final int MAX_SNIPPET_CHARS = 400;
    private static final int MAX_QUERY_CHARS = 200;

    private final PostMapper postMapper;
    private final RagQueryService ragQueryService;

    @Override
    public String name() {
        return "post_read";
    }

    @Override
    public String description() {
        return "读取一篇已知站内公开文章的相关正文片段。"
                + "当历史中已有 /post/{slug}，用户说‘看看这一篇’、要求总结或讨论该文时使用；"
                + "广泛找文章时仍使用 fulltext_search 或 article_rag。"
                + " overview 用于概览，focused 必须提供具体 query。";
    }

    @Override
    public Map<String, ParamDef> parameterSchema() {
        return Map.of(
                "slug", ParamDef.string("文章路径中的 slug，不含 /post/ 前缀", true, 1, 300),
                "mode", ParamDef.enumString("读取方式", false, List.of("overview", "focused")),
                "query", ParamDef.string("聚焦阅读时的具体问题", false, 1, 200),
                "topK", ParamDef.integer("返回正文片段数量", false, 1, MAX_TOP_K));
    }

    @Override
    public String execute(Map<String, Object> input, long userId) {
        String slug = normalizeSlug(string(input.get("slug")));
        if (!StringUtils.hasText(slug)) {
            return "STATUS: VALIDATION_ERROR\n缺少有效的文章 slug。";
        }

        PostDetailRow post = postMapper.findDetailBySlug(slug);
        if (!isPublicPublished(post)) {
            return "STATUS: NOT_ACCESSIBLE\n没有可公开读取的对应文章。";
        }
        if (!StringUtils.hasText(post.getContentUrl())) {
            return "STATUS: CONTENT_UNAVAILABLE\n文章可以访问，但正文暂时无法读取。";
        }

        String mode = normalizedMode(input.get("mode"));
        String query = string(input.get("query"));
        if ("focused".equals(mode) && !StringUtils.hasText(query)) {
            return "STATUS: VALIDATION_ERROR\nfocused 模式需要具体问题 query。";
        }
        String retrievalQuery = "focused".equals(mode)
                ? boundedQuery(query.trim())
                : overviewQuery(post);
        int topK = Math.min(Math.max(parseInt(input.get("topK"), DEFAULT_TOP_K), 1), MAX_TOP_K);

        List<SearchResult> results;
        try {
            results = ragQueryService.searchPost(post.getId(), retrievalQuery, topK);
        } catch (RuntimeException exception) {
            log.warn("Exact post RAG read failed for post {}", post.getId(), exception);
            return "STATUS: INDEX_FAILED\n文章可以访问，但相关正文片段暂时无法检索。";
        }
        List<String> snippets = boundedSnippets(results);
        if (snippets.isEmpty()) {
            return "STATUS: NO_RELEVANT_SNIPPETS\n"
                    + articleHeader(post)
                    + "\n文章可以访问，但当前问题没有取得足够的相关正文片段。";
        }

        StringBuilder output = new StringBuilder()
                .append("STATUS: SUCCESS\n")
                .append(articleHeader(post))
                .append("\n读取模式：").append(mode)
                .append("\n相关正文片段：");
        for (int index = 0; index < snippets.size(); index++) {
            output.append("\n[").append(index + 1).append("] ").append(snippets.get(index));
        }
        output.append("\n请仅依据以上片段总结或回答，并保留文章路径供用户打开。 ");
        return output.toString().stripTrailing();
    }

    private boolean isPublicPublished(PostDetailRow post) {
        return post != null
                && post.getId() != null
                && post.getId() > 0
                && "published".equalsIgnoreCase(post.getStatus())
                && "public".equalsIgnoreCase(post.getVisible());
    }

    private String overviewQuery(PostDetailRow post) {
        String title = StringUtils.hasText(post.getTitle()) ? post.getTitle().trim() : "";
        String description = StringUtils.hasText(post.getDescription())
                ? post.getDescription().trim()
                : "";
        String query = (title + " " + description).trim();
        return boundedQuery(query.isEmpty() ? post.getSlug() : query);
    }

    private String articleHeader(PostDetailRow post) {
        String title = StringUtils.hasText(post.getTitle()) ? post.getTitle().trim() : "文章";
        return "文章：《" + title + "》\n路径：/post/" + post.getSlug();
    }

    private List<String> boundedSnippets(List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        List<String> snippets = new ArrayList<>(Math.min(results.size(), MAX_TOP_K));
        for (SearchResult result : results) {
            if (result == null || !StringUtils.hasText(result.getSnippet())) {
                continue;
            }
            snippets.add(truncate(result.getSnippet().trim(), MAX_SNIPPET_CHARS));
            if (snippets.size() >= MAX_TOP_K) {
                break;
            }
        }
        return List.copyOf(snippets);
    }

    private String normalizeSlug(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String slug = value.trim();
        if (slug.startsWith("/post/")) {
            slug = slug.substring("/post/".length());
        }
        while (slug.startsWith("/")) {
            slug = slug.substring(1);
        }
        while (slug.endsWith("/")) {
            slug = slug.substring(0, slug.length() - 1);
        }
        return slug;
    }

    private String normalizedMode(Object value) {
        String mode = string(value);
        return mode != null && "focused".equalsIgnoreCase(mode.trim()) ? "focused" : "overview";
    }

    private int parseInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String truncate(String value, int maxChars) {
        return value.length() <= maxChars ? value : value.substring(0, maxChars) + "…";
    }

    private String boundedQuery(String value) {
        return value.length() <= MAX_QUERY_CHARS ? value : value.substring(0, MAX_QUERY_CHARS);
    }
}
