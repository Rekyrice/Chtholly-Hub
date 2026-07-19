package com.chtholly.llm.rag;

import com.chtholly.agent.CharacterSoulService;
import com.chtholly.agent.evidence.EvidenceSet;
import com.chtholly.agent.search.SearchResult;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.Post;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Retrieves only current, MySQL-authorized public RAG chunks. */
@Slf4j
@Service
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
@RequiredArgsConstructor
public class RagQueryService {

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final RagIndexService indexService;
    private final CharacterSoulService characterSoulService;
    private final PostMapper postMapper;

    /** Searches vector chunks and rejects missing, revoked, private, or stale post versions. */
    public List<SearchResult> search(String query, int topK) {
        if (!StringUtils.hasText(query) || topK <= 0) {
            return List.of();
        }
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder().query(query.trim()).topK(topK).build());
        if (docs == null || docs.isEmpty()) {
            return List.of();
        }
        Map<Long, Post> posts = loadPosts(docs);
        List<SearchResult> results = new ArrayList<>(docs.size());
        for (Document doc : docs) {
            Long postId = parsePostId(doc.getMetadata().get("postId"));
            Post post = postId == null ? null : posts.get(postId);
            if (!eligible(doc, post)) {
                continue;
            }
            String id = "post:" + postId;
            String sourceHash = currentContentHash(post);
            String sourceVersion = post.getUpdateTime() == null
                    ? "current"
                    : post.getUpdateTime().toString();
            results.add(new SearchResult(
                    id,
                    StringUtils.hasText(post.getTitle()) ? post.getTitle() : stringValue(doc.getMetadata().get("title"), "帖子片段"),
                    truncate(doc.getText(), 240),
                    "semantic",
                    doc.getScore() == null ? 0.0 : doc.getScore(),
                    id,
                    nullableString(doc.getMetadata().get("chunkId")),
                    sourceVersion,
                    sourceHash,
                    Set.of("PUBLIC")));
        }
        return List.copyOf(results);
    }

    public Flux<String> streamAnswerFlux(long postId, String question, int topK, int maxTokens) {
        return streamAnswerFlux(postId, question, List.of(), topK, maxTokens);
    }

    /** Streams a post-scoped answer only when current verified chunks are available. */
    public Flux<String> streamAnswerFlux(long postId,
                                         String question,
                                         List<RagConversationTurn> history,
                                         int topK,
                                         int maxTokens) {
        try {
            indexService.ensureIndexed(postId);
        } catch (RuntimeException exception) {
            log.warn("RAG index reconciliation failed for post {}: {}", postId, exception.getMessage());
            return Flux.just(EvidenceSet.INSUFFICIENT_EVIDENCE_ANSWER);
        }

        List<String> contexts;
        try {
            contexts = searchContexts(postId, question, Math.max(1, topK));
        } catch (RuntimeException exception) {
            log.warn("RAG context retrieval failed for post {}: {}", postId, exception.getMessage());
            return Flux.just(EvidenceSet.INSUFFICIENT_EVIDENCE_ANSWER);
        }
        if (contexts.isEmpty()) {
            return Flux.just(EvidenceSet.INSUFFICIENT_EVIDENCE_ANSWER);
        }

        PostQaPromptBuilder.Prompt prompt = PostQaPromptBuilder.build(
                characterSoulService.getSoulContent(), contexts, history, question);
        return chatClient
                .prompt()
                .system(prompt.system())
                .user(prompt.user())
                .options(DeepSeekChatOptions.builder()
                        .model("deepseek-chat")
                        .temperature(0.2)
                        .maxTokens(maxTokens)
                        .build())
                .stream()
                .content();
    }

    private List<String> searchContexts(long postId, String query, int topK) {
        Post post = postMapper.findById(postId);
        if (!isPublicPublished(post) || !StringUtils.hasText(currentContentHash(post))) {
            return List.of();
        }
        int fetchK = Math.max(topK * 3, 20);
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(fetchK).build());
        if (docs == null || docs.isEmpty()) {
            return List.of();
        }
        List<String> contexts = new ArrayList<>(topK);
        for (Document document : docs) {
            Long candidateId = parsePostId(document.getMetadata().get("postId"));
            if (candidateId != null && candidateId == postId && eligible(document, post)
                    && StringUtils.hasText(document.getText())) {
                contexts.add(document.getText());
                if (contexts.size() >= topK) {
                    break;
                }
            }
        }
        return List.copyOf(contexts);
    }

    private Map<Long, Post> loadPosts(List<Document> docs) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Document doc : docs) {
            Long postId = parsePostId(doc.getMetadata().get("postId"));
            if (postId != null) {
                ids.add(postId);
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<Post> rows = postMapper.findByIds(List.copyOf(ids));
        Map<Long, Post> posts = new LinkedHashMap<>();
        if (rows != null) {
            for (Post row : rows) {
                if (row != null && row.getId() != null) {
                    posts.put(row.getId(), row);
                }
            }
        }
        return Map.copyOf(posts);
    }

    private boolean eligible(Document document, Post post) {
        if (!isPublicPublished(post) || !StringUtils.hasText(document.getText())) {
            return false;
        }
        String indexedSha = nullableString(document.getMetadata().get("contentSha256"));
        if (StringUtils.hasText(post.getContentSha256())) {
            return post.getContentSha256().equalsIgnoreCase(indexedSha);
        }
        String indexedEtag = nullableString(document.getMetadata().get("contentEtag"));
        return StringUtils.hasText(post.getContentEtag()) && post.getContentEtag().equals(indexedEtag);
    }

    private boolean isPublicPublished(Post post) {
        return post != null
                && "published".equalsIgnoreCase(post.getStatus())
                && "public".equalsIgnoreCase(post.getVisible());
    }

    private String currentContentHash(Post post) {
        if (post == null) {
            return null;
        }
        return StringUtils.hasText(post.getContentSha256())
                ? post.getContentSha256()
                : nullableString(post.getContentEtag());
    }

    private Long parsePostId(Object value) {
        if (value == null) {
            return null;
        }
        try {
            long id = Long.parseLong(String.valueOf(value));
            return id > 0 ? id : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String stringValue(Object value, String fallback) {
        String text = nullableString(value);
        return text == null ? fallback : text;
    }

    private static String nullableString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private static String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...";
    }
}
