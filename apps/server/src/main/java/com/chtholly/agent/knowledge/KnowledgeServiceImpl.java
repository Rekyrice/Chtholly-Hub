package com.chtholly.agent.knowledge;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.chtholly.agent.anchor.KnowledgeService;
import com.chtholly.agent.search.SearchResult;
import com.chtholly.bangumi.model.BangumiSubjectRow;
import com.chtholly.bangumi.service.BangumiService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Markdown-backed character knowledge service for Chtholly's identity context.
 *
 * <p>The service loads classpath knowledge files at startup, keeps a local
 * deterministic retrieval index for tests and degraded mode, and mirrors chunks
 * into the independent {@code chtholly-knowledge} Elasticsearch index when ES is available.
 */
@Slf4j
@Service
public class KnowledgeServiceImpl implements KnowledgeService {

    private static final String RESOURCE_PATTERN = "classpath*:knowledge/*.md";
    private static final String INDEX_NAME = "chtholly-knowledge";
    private static final int CHUNK_TARGET_CHARS = 220;

    private final ResourcePatternResolver resourceResolver;
    private final ObjectProvider<ElasticsearchClient> esClientProvider;
    private final ObjectProvider<BangumiService> bangumiServiceProvider;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final List<KnowledgeChunk> chunks = new ArrayList<>();

    @Autowired
    public KnowledgeServiceImpl(ResourcePatternResolver resourceResolver,
                                ObjectProvider<ElasticsearchClient> esClientProvider,
                                ObjectProvider<BangumiService> bangumiServiceProvider,
                                ObjectProvider<EmbeddingModel> embeddingModelProvider) {
        this.resourceResolver = resourceResolver;
        this.esClientProvider = esClientProvider;
        this.bangumiServiceProvider = bangumiServiceProvider;
        this.embeddingModelProvider = embeddingModelProvider;
    }

    KnowledgeServiceImpl(ResourcePatternResolver resourceResolver,
                         ObjectProvider<ElasticsearchClient> esClientProvider,
                         ObjectProvider<BangumiService> bangumiServiceProvider) {
        this(resourceResolver, esClientProvider, bangumiServiceProvider, null);
    }

    /**
     * Loads Markdown knowledge files and mirrors chunks into Elasticsearch when possible.
     *
     * @throws IOException if classpath resources cannot be scanned.
     */
    @PostConstruct
    public void loadKnowledge() throws IOException {
        Resource[] resources = resourceResolver.getResources(RESOURCE_PATTERN);
        List<Resource> sorted = new ArrayList<>(List.of(resources));
        sorted.sort(Comparator.comparing(resource -> String.valueOf(resource.getFilename())));

        List<KnowledgeChunk> loaded = new ArrayList<>();
        for (Resource resource : sorted) {
            if (resource == null || !resource.exists()) {
                continue;
            }
            loaded.addAll(splitResource(resource));
        }

        synchronized (chunks) {
            chunks.clear();
            chunks.addAll(loaded);
        }
        log.info("Loaded {} Chtholly knowledge chunks from {} files", loaded.size(), sorted.size());
        mirrorToElasticsearch(loaded);
    }

    /**
     * Session semantic anchor remains query-driven in {@link #searchRelevantKnowledge(String, int)}.
     *
     * @param userId    Authenticated user ID.
     * @param sessionId Conversation session ID.
     * @return Empty list for non-query anchor assembly.
     */
    @Override
    public List<String> getRelevantKnowledge(long userId, String sessionId) {
        return List.of();
    }

    /**
     * Searches Markdown knowledge chunks with deterministic local ranking.
     *
     * @param query Current user question.
     * @param topK  Maximum snippet count.
     * @return Relevant character knowledge snippets.
     */
    @Override
    public List<String> searchRelevantKnowledge(String query, int topK) {
        if (!StringUtils.hasText(query) || topK <= 0) {
            return List.of();
        }
        List<KnowledgeChunk> snapshot;
        synchronized (chunks) {
            snapshot = List.copyOf(chunks);
        }
        if (snapshot.isEmpty()) {
            return List.of();
        }

        Set<String> terms = queryTerms(query);
        return snapshot.stream()
                .map(chunk -> new ScoredChunk(chunk, score(chunk, terms)))
                .filter(scored -> scored.score() > 0.0)
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed()
                        .thenComparing(scored -> scored.chunk().source())
                        .thenComparingInt(scored -> scored.chunk().index()))
                .limit(Math.min(topK, 10))
                .map(scored -> scored.chunk().text())
                .toList();
    }

    /**
     * Searches Bangumi subjects as structured entity knowledge.
     *
     * @param query Query text.
     * @param topK  Maximum result count.
     * @return Bangumi entity results.
     */
    @Override
    public List<SearchResult> searchEntities(String query, int topK) {
        if (!StringUtils.hasText(query) || topK <= 0) {
            return List.of();
        }
        BangumiService bangumiService = bangumiServiceProvider.getIfAvailable();
        if (bangumiService == null) {
            return List.of();
        }

        List<BangumiSubjectRow> rows = bangumiService.search(query.trim(), topK);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }

        List<SearchResult> results = new ArrayList<>(rows.size());
        for (BangumiSubjectRow row : rows) {
            if (row == null || row.getId() == null) {
                continue;
            }
            String title = StringUtils.hasText(row.getNameCn()) ? row.getNameCn() : row.getName();
            results.add(new SearchResult(
                    "bangumi:" + row.getId(),
                    title,
                    buildEntitySnippet(row),
                    "entity",
                    0.0));
        }
        return List.copyOf(results);
    }

    private List<KnowledgeChunk> splitResource(Resource resource) throws IOException {
        String filename = StringUtils.hasText(resource.getFilename()) ? resource.getFilename() : "knowledge.md";
        String markdown = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String title = extractTitle(markdown, filename);
        String[] paragraphs = markdown.replace("\r\n", "\n").split("\\n\\s*\\n");

        List<KnowledgeChunk> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int index = 0;
        for (String paragraph : paragraphs) {
            String cleaned = cleanParagraph(paragraph);
            if (!StringUtils.hasText(cleaned) || cleaned.equals(title)) {
                continue;
            }
            if (!current.isEmpty() && current.length() + cleaned.length() > CHUNK_TARGET_CHARS) {
                out.add(new KnowledgeChunk(chunkId(filename, index), filename, title, index, current.toString()));
                current.setLength(0);
                index++;
            }
            if (!current.isEmpty()) {
                current.append('\n');
            }
            current.append(cleaned);
        }
        if (!current.isEmpty()) {
            out.add(new KnowledgeChunk(chunkId(filename, index), filename, title, index, current.toString()));
        }
        return out;
    }

    private void mirrorToElasticsearch(List<KnowledgeChunk> loaded) {
        ElasticsearchClient esClient = esClientProvider.getIfAvailable();
        if (esClient == null || loaded.isEmpty()) {
            return;
        }
        EmbeddingModel embeddingModel = embeddingModelProvider == null ? null : embeddingModelProvider.getIfAvailable();
        try {
            boolean exists = esClient.indices().exists(request -> request.index(INDEX_NAME)).value();
            if (!exists) {
                esClient.indices().create(request -> request.index(INDEX_NAME));
            }
            for (KnowledgeChunk chunk : loaded) {
                Map<String, Object> document = new LinkedHashMap<>();
                document.put("id", chunk.id());
                document.put("source", chunk.source());
                document.put("title", chunk.title());
                document.put("chunkIndex", chunk.index());
                document.put("text", chunk.text());
                List<Float> embedding = embedChunk(embeddingModel, chunk.text());
                if (!embedding.isEmpty()) {
                    document.put("embedding", embedding);
                }
                esClient.index(request -> request.index(INDEX_NAME).id(chunk.id()).document(document));
            }
        } catch (Exception e) {
            log.warn("Failed to mirror Chtholly knowledge into Elasticsearch: {}", e.getMessage(), e);
        }
    }

    private static String buildEntitySnippet(BangumiSubjectRow row) {
        List<String> parts = new ArrayList<>();
        BigDecimal score = row.getScore();
        if (score != null) {
            parts.add("评分 " + score);
        }
        if (row.getRank() != null) {
            parts.add("排名 " + row.getRank());
        }
        if (row.getEpsCount() != null) {
            parts.add("集数 " + row.getEpsCount());
        }
        if (StringUtils.hasText(row.getSummary())) {
            parts.add(truncate(row.getSummary(), 140));
        }
        return String.join("；", parts);
    }

    private List<Float> embedChunk(EmbeddingModel embeddingModel, String text) {
        if (embeddingModel == null || !StringUtils.hasText(text)) {
            return List.of();
        }
        try {
            float[] vector = embeddingModel.embed(text);
            if (vector == null || vector.length == 0) {
                return List.of();
            }
            List<Float> values = new ArrayList<>(vector.length);
            for (float value : vector) {
                values.add(value);
            }
            return values;
        } catch (Exception e) {
            log.warn("Failed to embed Chtholly knowledge chunk: {}", e.getMessage());
            return List.of();
        }
    }

    private static Set<String> queryTerms(String query) {
        String normalized = query.trim().toLowerCase();
        Set<String> terms = new LinkedHashSet<>();
        for (String token : normalized.split("[\\s，。！？、；：,.!?;:《》“”\"'()（）\\[\\]【】]+")) {
            if (token.length() >= 2) {
                terms.add(token);
            }
        }
        for (int i = 0; i < normalized.length() - 1; i++) {
            char first = normalized.charAt(i);
            char second = normalized.charAt(i + 1);
            if (Character.isLetterOrDigit(first) || isCjk(first)) {
                if (Character.isLetterOrDigit(second) || isCjk(second)) {
                    terms.add(normalized.substring(i, i + 2));
                }
            }
        }
        addKnownTerm(normalized, terms, "珂朵莉");
        addKnownTerm(normalized, terms, "芙莉莲");
        addKnownTerm(normalized, terms, "夏目");
        addKnownTerm(normalized, terms, "轻音");
        addKnownTerm(normalized, terms, "虫师");
        addKnownTerm(normalized, terms, "紫罗兰");
        addKnownTerm(normalized, terms, "clannad");
        addKnownTerm(normalized, terms, "air");
        addKnownTerm(normalized, terms, "aria");
        return terms;
    }

    private static double score(KnowledgeChunk chunk, Set<String> terms) {
        String haystack = (chunk.title() + "\n" + chunk.text()).toLowerCase();
        double score = 0.0;
        for (String term : terms) {
            if (haystack.contains(term)) {
                score += Math.max(1, term.length());
            }
        }
        return score;
    }

    private static void addKnownTerm(String query, Set<String> terms, String term) {
        if (query.contains(term.toLowerCase())) {
            terms.add(term.toLowerCase());
        }
    }

    private static boolean isCjk(char c) {
        Character.UnicodeScript script = Character.UnicodeScript.of(c);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA;
    }

    private static String extractTitle(String markdown, String fallback) {
        for (String line : markdown.replace("\r\n", "\n").split("\n")) {
            if (line.startsWith("# ")) {
                return line.substring(2).trim();
            }
        }
        return fallback.replace(".md", "");
    }

    private static String cleanParagraph(String paragraph) {
        String cleaned = paragraph.trim();
        if (cleaned.startsWith("# ")) {
            return cleaned.substring(2).trim();
        }
        return cleaned.replaceAll("\\s*\\n\\s*", "\n").trim();
    }

    private static String chunkId(String filename, int index) {
        return filename.replace(".md", "") + ":" + index;
    }

    private static String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...";
    }

    private record KnowledgeChunk(String id, String source, String title, int index, String text) {
    }

    private record ScoredChunk(KnowledgeChunk chunk, double score) {
    }
}
