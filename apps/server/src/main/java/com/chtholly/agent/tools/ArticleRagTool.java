package com.chtholly.agent.tools;

import com.chtholly.agent.AgentTool;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.Post;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 站内文章语义检索（向量 RAG）。 */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
@RequiredArgsConstructor
public class ArticleRagTool implements AgentTool {

    private final VectorStore vectorStore;
    private final PostMapper postMapper;

    @Override
    public String name() {
        return "article_rag";
    }

    @Override
    public String description() {
        return "按语义检索站内 Markdown 帖子片段（仅博客，不含 Bangumi）。input: {\"query\":\"问题或关键词\",\"topK\":5}";
    }

    @Override
    public String execute(Map<String, Object> input, long userId) {
        String query = str(input.get("query"));
        if (query == null || query.isBlank()) {
            return "错误：缺少参数 query";
        }
        int topK = parseInt(input.get("topK"), 5);
        topK = Math.min(Math.max(topK, 1), 10);

        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder().query(query.trim()).topK(topK).build()
        );
        if (docs.isEmpty()) {
            return "向量库中未找到与「" + query + "」相关的帖子片段（可能尚未建立 RAG 索引）。";
        }

        Map<String, Post> postById = loadPostsByIds(docs);

        List<String> blocks = new ArrayList<>();
        for (Document doc : docs) {
            Object postIdObj = doc.getMetadata().get("postId");
            String postId = postIdObj == null ? null : String.valueOf(postIdObj);
            String title = str(doc.getMetadata().get("title"));
            String slug = null;
            if (postId != null) {
                Post post = postById.get(postId);
                if (post != null) {
                    title = post.getTitle() != null ? post.getTitle() : title;
                    slug = post.getSlug();
                }
            }
            String text = doc.getText();
            if (text != null && text.length() > 400) {
                text = text.substring(0, 400) + "…";
            }
            blocks.add("【" + (title == null ? "帖子" : title) + "】"
                    + (slug != null ? " (/post/" + slug + ")" : "")
                    + "\n" + (text == null ? "" : text));
        }
        return String.join("\n\n---\n\n", blocks);
    }

    private Map<String, Post> loadPostsByIds(List<Document> docs) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Document doc : docs) {
            Object postIdObj = doc.getMetadata().get("postId");
            if (postIdObj == null) {
                continue;
            }
            try {
                ids.add(Long.parseLong(String.valueOf(postIdObj)));
            } catch (NumberFormatException ignored) {
                // 跳过非法 postId
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<Post> posts = postMapper.findByIds(new ArrayList<>(ids));
        return posts.stream()
                .filter(p -> p.getId() != null)
                .collect(Collectors.toMap(p -> String.valueOf(p.getId()), Function.identity(), (a, b) -> a, LinkedHashMap::new));
    }

    private int parseInt(Object v, int defaultVal) {
        if (v == null) {
            return defaultVal;
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
