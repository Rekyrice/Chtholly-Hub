package com.chtholly.agent.content;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.chtholly.post.api.dto.PostDetailResponse;
import com.chtholly.post.model.Post;
import com.chtholly.post.service.PostService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scheduled content understanding for published posts.
 */
@Slf4j
@Service
public class ContentUnderstandingService {

    private static final String INDEX = "chtholly_content_index";

    private final PostService postService;
    private final ElasticsearchClient esClient;
    private final ObjectMapper objectMapper;
    private final TextGenerator textGenerator;
    private final ContentFetcher contentFetcher;
    private final Clock clock;

    public ContentUnderstandingService(PostService postService,
                                       ElasticsearchClient esClient,
                                       ObjectMapper objectMapper,
                                       ObjectProvider<ChatClient> chatClientProvider) {
        this(postService,
                esClient,
                objectMapper,
                prompt -> generateWithChatClient(chatClientProvider.getIfAvailable(), prompt),
                ContentUnderstandingService::fetchContentFromUrl,
                Clock.systemUTC());
    }

    ContentUnderstandingService(PostService postService,
                                ElasticsearchClient esClient,
                                ObjectMapper objectMapper,
                                TextGenerator textGenerator,
                                ContentFetcher contentFetcher,
                                Clock clock) {
        this.postService = postService;
        this.esClient = esClient;
        this.objectMapper = objectMapper;
        this.textGenerator = textGenerator;
        this.contentFetcher = contentFetcher;
        this.clock = clock;
    }

    /**
     * Scheduled scan: every 30 minutes, process new or updated articles.
     */
    @Scheduled(fixedDelay = 1_800_000L, initialDelay = 60_000L)
    public void scanAndUnderstand() {
        List<Post> pending = postService.getPostsNeedingUnderstanding();
        if (pending == null || pending.isEmpty()) {
            return;
        }
        for (Post post : pending) {
            try {
                ContentAnalysis analysis = analyzePost(post);
                storeAnalysis(post.getId(), analysis);
            } catch (Exception e) {
                log.warn("Content understanding failed, postId={}: {}", post == null ? null : post.getId(), e.getMessage(), e);
            }
        }
    }

    /**
     * Returns stored content analysis for a post.
     *
     * @param postId post ID
     * @return analysis or null
     */
    public ContentAnalysis getAnalysis(Long postId) {
        if (postId == null) {
            return null;
        }
        return postService.getContentAnalysis(postId);
    }

    /**
     * Returns related posts based on shared entities.
     *
     * @param postId source post ID
     * @return related post list
     */
    public List<RelatedPostDto> getRelatedPosts(Long postId) {
        ContentAnalysis analysis = getAnalysis(postId);
        if (analysis == null || analysis.relatedPostIds() == null || analysis.relatedPostIds().isEmpty()) {
            return List.of();
        }
        Set<String> sourceEntities = entityNameSet(analysis.entities());
        List<RelatedPostDto> related = new ArrayList<>();
        for (Long relatedId : analysis.relatedPostIds()) {
            if (relatedId == null) {
                continue;
            }
            PostDetailResponse detail = postService.getDetail(relatedId, null);
            ContentAnalysis relatedAnalysis = postService.getContentAnalysis(relatedId);
            List<String> shared = sharedEntities(sourceEntities, relatedAnalysis == null ? List.of() : relatedAnalysis.entities());
            related.add(new RelatedPostDto(
                    relatedId,
                    detail == null ? "" : detail.title(),
                    relatedAnalysis == null ? "" : relatedAnalysis.summary(),
                    shared));
        }
        return related;
    }

    private ContentAnalysis analyzePost(Post post) {
        String body = contentFetcher.fetch(post);
        if (body == null || body.isBlank()) {
            body = post.getDescription();
        }
        if (body == null) {
            body = "";
        }
        String truncated = truncate(body, 3000);
        String entityPrompt = """
                从以下文章中提取所有提到的实体。
                分类为：动漫作品名、角色名、技术概念、其他专有名词。
                每个实体给出置信度 (0-1)。
                输出 JSON 数组，字段为 name、category、confidence。

                文章内容：
                %s
                """.formatted(truncated);
        String summaryPrompt = """
                你是珂朵莉。用 1-2 句话总结这篇文章的核心内容。
                风格：安静地读完后说一句自己的理解。
                不要用"这篇文章讲述了..."，而是"原来是在说..."或"是关于...的呢"。

                文章内容：
                %s
                """.formatted(truncated);

        List<Entity> entities = parseEntities(textGenerator.generate(entityPrompt));
        String summary = cleanSummary(textGenerator.generate(summaryPrompt));
        List<Long> relatedIds = findRelatedPosts(post.getId(), entities);
        return new ContentAnalysis(entities, summary, relatedIds, clock.instant());
    }

    private void storeAnalysis(Long postId, ContentAnalysis analysis) {
        postService.saveContentAnalysis(postId, analysis);
        Map<String, Object> doc = Map.of(
                "contentAnalysis", Map.of(
                        "entities", analysis.entities(),
                        "summary", analysis.summary(),
                        "relatedPostIds", analysis.relatedPostIds(),
                        "analyzedAt", analysis.analyzedAt().toString()
                )
        );
        try {
            esClient.update(u -> u
                    .index(INDEX)
                    .id(String.valueOf(postId))
                    .doc(doc), Map.class);
        } catch (Exception e) {
            log.warn("Store content analysis in ES failed, postId={}: {}", postId, e.getMessage(), e);
        }
    }

    private List<Long> findRelatedPosts(Long postId, List<Entity> entities) {
        List<String> entityNames = entities == null ? List.of() : entities.stream()
                .filter(entity -> entity != null && entity.confidence() > 0.7 && entity.name() != null && !entity.name().isBlank())
                .map(Entity::name)
                .distinct()
                .toList();
        if (entityNames.size() < 2) {
            return List.of();
        }
        try {
            SearchResponse<Map> response = esClient.search(s -> s
                    .index(INDEX)
                    .size(10)
                    .query(q -> q.bool(b -> b
                            .must(m -> m.terms(t -> t
                                    .field("contentAnalysis.entities.name")
                                    .terms(tv -> tv.value(entityNames.stream().map(co.elastic.clients.elasticsearch._types.FieldValue::of).toList()))))
                            .mustNot(m -> m.term(t -> t.field("content_id").value(postId))))), Map.class);
            if (response.hits() == null || response.hits().hits() == null) {
                return List.of();
            }
            List<Long> result = new ArrayList<>();
            for (Hit<Map> hit : response.hits().hits()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> source = (Map<String, Object>) hit.source();
                Long id = asLong(source == null ? null : source.get("content_id"));
                if (id == null || id.equals(postId)) {
                    continue;
                }
                int sharedCount = sharedEntities(new LinkedHashSet<>(entityNames), readEntities(source)).size();
                if (sharedCount >= 2) {
                    result.add(id);
                }
                if (result.size() >= 5) {
                    break;
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Find related posts failed, postId={}: {}", postId, e.getMessage(), e);
            return List.of();
        }
    }

    private List<Entity> parseEntities(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Entity>>() {
            });
        } catch (Exception e) {
            log.warn("Parse content entities failed: {}", e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Entity> readEntities(Map<String, Object> source) {
        if (source == null || !(source.get("contentAnalysis") instanceof Map<?, ?> contentAnalysis)) {
            return List.of();
        }
        Object raw = contentAnalysis.get("entities");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Entity> entities = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Object name = map.get("name");
                Object category = map.get("category");
                Object confidence = map.get("confidence");
                entities.add(new Entity(String.valueOf(name), String.valueOf(category), asDouble(confidence)));
            }
        }
        return entities;
    }

    private static Set<String> entityNameSet(List<Entity> entities) {
        Set<String> names = new LinkedHashSet<>();
        if (entities == null) {
            return names;
        }
        for (Entity entity : entities) {
            if (entity != null && entity.name() != null && !entity.name().isBlank()) {
                names.add(entity.name());
            }
        }
        return names;
    }

    private static List<String> sharedEntities(Set<String> sourceEntities, List<Entity> targetEntities) {
        if (sourceEntities == null || sourceEntities.isEmpty() || targetEntities == null) {
            return List.of();
        }
        List<String> shared = new ArrayList<>();
        for (Entity entity : targetEntities) {
            if (entity != null && sourceEntities.contains(entity.name())) {
                shared.add(entity.name());
            }
        }
        return shared.stream().distinct().toList();
    }

    private static String cleanSummary(String summary) {
        return summary == null ? "" : summary.trim();
    }

    private static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxChars);
    }

    private static Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }
        return 0.0;
    }

    private static String fetchContentFromUrl(Post post) {
        if (post == null || post.getContentUrl() == null || post.getContentUrl().isBlank()) {
            return "";
        }
        try {
            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(List.of(MediaType.TEXT_PLAIN, MediaType.TEXT_HTML, MediaType.APPLICATION_JSON));
            ResponseEntity<byte[]> response = restTemplate.exchange(
                    post.getContentUrl(), HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
            byte[] body = response.getBody();
            return body == null ? "" : new String(body, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Fetch post body for content understanding failed, postId={}: {}", post.getId(), e.getMessage());
            return "";
        }
    }

    private static String generateWithChatClient(ChatClient chatClient, String prompt) {
        if (chatClient == null) {
            return "";
        }
        return chatClient.prompt()
                .user(prompt)
                .call()
                .content();
    }

    @FunctionalInterface
    interface TextGenerator {
        String generate(String prompt);
    }

    @FunctionalInterface
    interface ContentFetcher {
        String fetch(Post post);
    }
}
