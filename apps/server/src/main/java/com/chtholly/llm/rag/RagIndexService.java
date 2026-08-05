package com.chtholly.llm.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import co.elastic.clients.elasticsearch.indices.RefreshResponse;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.PostDetailRow;
import com.chtholly.config.EsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Builds the replayable RAG projection for public, published posts.
 *
 * <p>Each post mutation is serialized across nodes. A completion manifest is
 * published only after all deterministic chunks are query-visible, preventing
 * partial bulk writes from being acknowledged as a complete projection.</p>
 */
@Service
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class RagIndexService implements PostRagIndexer {
    private static final Logger log = LoggerFactory.getLogger(RagIndexService.class);
    private static final int COMPLETION_FORMAT_VERSION = 1;
    // 向量库封装（Elasticsearch VectorStore），负责写入/检索向量
    private final VectorStore vectorStore;
    // 数据访问：根据 postId 查询帖子详情（含 contentUrl、指纹等）
    private final PostMapper postMapper;
    // 跨实例串行化同一文章的索引写入与隐私删除
    private final RagPostMutationLock mutationLock;
    // 拉取 Markdown 正文内容
    private final RestTemplate http;
    // 直接使用 ES 客户端做指纹判断和删除旧切片
    private final ElasticsearchClient es;
    // ES 相关配置（索引名等）
    private final EsProperties esProps;

    /**
     * Creates the RAG projection service.
     *
     * @param vectorStore vector embedding store
     * @param postMapper authoritative post reader
     * @param mutationLock cross-node post mutation lock
     * @param http bounded content client
     * @param es Elasticsearch administration client
     * @param esProps Elasticsearch index properties
     */
    public RagIndexService(
            VectorStore vectorStore,
            PostMapper postMapper,
            RagPostMutationLock mutationLock,
            @Qualifier("searchContentRestTemplate") RestTemplate http,
            ElasticsearchClient es,
            EsProperties esProps) {
        this.vectorStore = Objects.requireNonNull(vectorStore, "vectorStore");
        this.postMapper = Objects.requireNonNull(postMapper, "postMapper");
        this.mutationLock = Objects.requireNonNull(mutationLock, "mutationLock");
        this.http = Objects.requireNonNull(http, "http");
        this.es = Objects.requireNonNull(es, "es");
        this.esProps = Objects.requireNonNull(esProps, "esProps");
    }

    @Override
    public void ensureIndexed(long postId) {
        // 当前策略：在问答前直接尝试重建（指纹未变化时会跳过）
        reindexSinglePost(postId);
    }

    /**
     * Rebuilds one post projection when its durable completion fact is stale.
     *
     * @param postId authoritative post ID
     * @return number of chunks written, or zero when no rebuild was required
     */
    public int reindexSinglePost(long postId) {
        return mutationLock.withLock(postId, () -> reindexSinglePostLocked(postId));
    }

    private int reindexSinglePostLocked(long postId) {
        PostDetailRow row = postMapper.findDetailById(postId);
        if (row == null) {
            removeIndexedLocked(postId);
            return 0;
        }

        // MySQL visibility is authoritative: private/deleted rows must remove old chunks.
        if (!"published".equalsIgnoreCase(row.getStatus()) || !"public".equalsIgnoreCase(row.getVisible())) {
            removeIndexedLocked(postId);
            return 0;
        }

        // A public row without retrievable content must not retain an older vector snapshot.
        if (!StringUtils.hasText(row.getContentUrl())) {
            removeIndexedLocked(postId);
            return 0;
        }

        String sourceFingerprint = sourceFingerprint(row);
        if (isUpToDate(postId, sourceFingerprint)) {
            log.info("Post {} already indexed with same fingerprint, skip", postId);
            return 0;
        }

        String text = fetchContent(row.getContentUrl());
        if (!StringUtils.hasText(text)) {
            removeIndexedLocked(postId);
            return 0;
        }

        // 先按 Markdown 标题切段，再做固定长度切片（带重叠）
        List<String> chunks = chunkMarkdown(text);
        // A bulk vector write can partially succeed. Remove the durable completion fact first so
        // a later Outbox replay never mistakes one surviving chunk for a complete projection.
        deleteCompletionManifest(postId);
        // 幂等 upsert：先删除旧切片
        deleteExistingChunks(postId);

        // 组装 Document（文本 + 业务元数据），用于向量写入与检索过滤
        List<Document> docs = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            String cid = "post:" + postId + ":" + sourceFingerprint + ":" + i;
            Map<String, Object> meta = new HashMap<>();
            meta.put("postId", String.valueOf(postId));
            meta.put("chunkId", cid);
            meta.put("position", i);
            meta.put("contentEtag", row.getContentEtag());
            meta.put("contentSha256", row.getContentSha256());
            meta.put("sourceFingerprint", sourceFingerprint);
            meta.put("contentUrl", row.getContentUrl());
            meta.put("title", row.getTitle());
            docs.add(new Document(cid, chunks.get(i), meta));
        }
        try {
            vectorStore.add(docs);
        } catch (Exception e) {
            throw new IllegalStateException("vector index write failed for post " + postId, e);
        }
        refreshIndex(postId);
        writeCompletionManifest(postId, sourceFingerprint, docs.size());
        // 返回本次写入的切片数量
        return docs.size();
    }

    /**
     * 指纹判断是否需要重建：
     * - 以 postId 查询任意一条已索引文档的 metadata
     * - 优先比较 SHA256，其次比较 ETag；一致则视为无需重建
     */
    private boolean isUpToDate(long postId, String sourceFingerprint) {
        try {
            requireIndexName();
            GetResponse<Map> response = es.get(get -> get
                            .index(esProps.getIndex())
                            .id(completionManifestId(postId))
                            .realtime(true),
                    Map.class);
            if (response == null || !response.found() || response.source() == null) {
                return false;
            }
            Map source = response.source();
            return Objects.equals("rag_completion", asString(source.get("documentType")))
                    && Objects.equals(sourceFingerprint, asString(source.get("sourceFingerprint")))
                    && asInt(source.get("formatVersion")) == COMPLETION_FORMAT_VERSION
                    && asInt(source.get("chunkCount")) > 0;
        } catch (Exception e) {
            log.warn("RAG completion manifest check failed for post {}: {}", postId, e.getMessage());
            return false;
        }
    }

    private void deleteCompletionManifest(long postId) {
        try {
            requireIndexName();
            es.delete(delete -> delete
                    .index(esProps.getIndex())
                    .id(completionManifestId(postId))
                    .refresh(Refresh.WaitFor));
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                return;
            }
            throw new IllegalStateException(
                    "delete RAG completion manifest failed for post " + postId,
                    e);
        } catch (Exception e) {
            throw new IllegalStateException("delete RAG completion manifest failed for post " + postId, e);
        }
    }

    private void writeCompletionManifest(long postId, String sourceFingerprint, int chunkCount) {
        if (chunkCount <= 0) {
            throw new IllegalArgumentException("RAG completion manifest requires at least one chunk");
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("documentType", "rag_completion");
        manifest.put("postId", String.valueOf(postId));
        manifest.put("sourceFingerprint", sourceFingerprint);
        manifest.put("chunkCount", chunkCount);
        manifest.put("formatVersion", COMPLETION_FORMAT_VERSION);
        try {
            requireIndexName();
            es.index(index -> index
                    .index(esProps.getIndex())
                    .id(completionManifestId(postId))
                    .document(manifest)
                    .refresh(Refresh.WaitFor));
        } catch (Exception e) {
            throw new IllegalStateException("write RAG completion manifest failed for post " + postId, e);
        }
    }

    /**
     * 删除旧切片：按 metadata.postId 精确删除，确保 upsert 幂等
     */
    private void deleteExistingChunks(long postId) {
        try {
            requireIndexName();
            DeleteByQueryResponse response = es.deleteByQuery(d -> d
                    .index(esProps.getIndex())
                    .query(q -> q.term(t -> t
                            .field("metadata.postId")
                            .value(v -> v.stringValue(String.valueOf(postId))))));
            if (response == null
                    || Boolean.TRUE.equals(response.timedOut())
                    || response.versionConflicts() != null
                    && response.versionConflicts() > 0L
                    || response.failures() != null
                    && !response.failures().isEmpty()) {
                throw new IllegalStateException(
                        "incomplete stale vector chunk deletion for post " + postId);
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("delete stale vector chunks failed for post " + postId, e);
        }
    }

    /**
     * Removes all chunks and waits until the privacy change is query-visible.
     *
     * @param postId authoritative post ID
     */
    public void removeIndexed(long postId) {
        mutationLock.withLock(postId, () -> {
            removeIndexedLocked(postId);
            return 0;
        });
    }

    private void removeIndexedLocked(long postId) {
        deleteCompletionManifest(postId);
        deleteExistingChunks(postId);
        refreshIndex(postId);
    }

    private void refreshIndex(long postId) {
        try {
            requireIndexName();
            RefreshResponse response = es.indices()
                    .refresh(refresh -> refresh.index(esProps.getIndex()));
            if (response == null
                    || response.shards() == null
                    || response.shards().failed() == null
                    || response.shards().failed().longValue() > 0L) {
                throw new IllegalStateException(
                        "incomplete vector index refresh for post " + postId);
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Vector index refresh failed for post " + postId, e);
        }
    }

    private void requireIndexName() {
        if (!StringUtils.hasText(esProps.getIndex())) {
            throw new IllegalStateException("Vector index name is not configured");
        }
    }

    private static String sourceFingerprint(PostDetailRow row) {
        String source = String.join("\u001F",
                safe(row.getContentSha256()),
                safe(row.getContentEtag()),
                safe(row.getContentUrl()),
                safe(row.getTitle()),
                safe(row.getDescription()),
                safe(row.getTags()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String asString(Object o) {
        // 统一处理 null → String 的转换
        return o == null ? null : String.valueOf(o);
    }

    private static int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? -1 : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String completionManifestId(long postId) {
        return "rag-completion:" + postId;
    }

    /**
     * 拉取正文内容（Markdown 文本）。
     */
    private String fetchContent(String url) {
        try {
            return http.getForObject(url, String.class);
        } catch (Exception e) {
            throw new IllegalStateException("Post content fetch failed: " + url, e);
        }
    }

    /**
     * 按 Markdown 标题切段，再交由固定长度切片策略处理。
     */
    private List<String> chunkMarkdown(String text) {
        List<String> paras = new ArrayList<>();
        String[] lines = text.split("\r?\n");
        StringBuilder buf = new StringBuilder();
        for (String line : lines) {
            boolean isHeader = line.startsWith("#");
            if (isHeader && !buf.isEmpty()) { // 遇到新的标题，收束上一段
                paras.add(buf.toString());
                buf.setLength(0);
            }
            buf.append(line).append('\n');
        }
        if (!buf.isEmpty()) paras.add(buf.toString());

        return getChunks(paras);
    }

    /**
     * 固定长度切片（每片 ≤ 800 字符），切片间 100 字符重叠：
     * - 兼顾检索召回与上下文连续性
     */
    private static List<String> getChunks(List<String> paras) {
        List<String> chunks = new ArrayList<>();
        for (String p : paras) {
            if (p.length() <= 800) {
                chunks.add(p);
            } else {
                int start = 0;
                while (start < p.length()) {
                    int end = Math.min(start + 800, p.length());
                    chunks.add(p.substring(start, end));
                    if (end >= p.length()) break;
                    start = Math.max(end - 100, start + 1); // 重叠 100 字符以保留语义连续
                }
            }
        }
        return chunks;
    }
}
