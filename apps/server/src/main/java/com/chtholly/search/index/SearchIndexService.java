package com.chtholly.search.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import co.elastic.clients.elasticsearch._types.Refresh;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chtholly.counter.service.CounterService;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.PostDetailRow;
import com.chtholly.post.model.PostFeedRow;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 搜索索引写入服务：负责 upsert/软删 以及首次启动的索引回灌。
 */
@Service
public class SearchIndexService {
    private static final Logger log = LoggerFactory.getLogger(SearchIndexService.class);
    private static final String INDEX = "chtholly_content_index";

    private final ElasticsearchClient es;
    private final PostMapper postMapper;
    private final CounterService counterService;
    private final ObjectMapper objectMapper;
    private final RestTemplate contentRestTemplate;
    private final String contentBaseUrl;

    public SearchIndexService(
            ElasticsearchClient es,
            PostMapper postMapper,
            CounterService counterService,
            ObjectMapper objectMapper,
            @Qualifier("searchContentRestTemplate") RestTemplate contentRestTemplate,
            @Value("${search.content-base-url:http://localhost:8888}") String contentBaseUrl) {
        this.es = es;
        this.postMapper = postMapper;
        this.counterService = counterService;
        this.objectMapper = objectMapper;
        this.contentRestTemplate = contentRestTemplate;
        this.contentBaseUrl = contentBaseUrl == null ? "" : contentBaseUrl.replaceAll("/+$", "");
    }

    /**
     * 索引为空时回灌已发布帖子（由 SearchIndexInitializer 在索引就绪后调用）。
     */
    public void ensureBackfill() {
        try {
            if (!es.indices().exists(e -> e.index(INDEX)).value()) {
                log.warn("Search index backfill skipped: index {} not found", INDEX);
                return;
            }
            long mysqlPublished = postMapper.countFeedPublic();
            long esPublished = countPublishedDocuments();
            if (mysqlPublished <= esPublished) {
                return;
            }
            log.info("Search index backfill started: mysqlPublished={} esPublished={}",
                    mysqlPublished, esPublished);
            int indexed = backfillPublishedPosts();
            log.info("Search index backfill completed: {} posts processed, {} published documents in index",
                    indexed, countPublishedDocuments());
        } catch (Exception e) {
            log.warn("Search index backfill skipped: {}", e.getMessage());
        }
    }

    /** 将 MySQL 中全部公开已发布帖子 upsert 到 ES（幂等，供 seed 与启动回灌复用）。 */
    public int backfillPublishedPosts() {
        int limit = 500;
        int offset = 0;
        int indexed = 0;
        while (true) {
            List<PostFeedRow> rows = postMapper.listFeedPublic(limit, offset);
            if (rows == null || rows.isEmpty()) {
                break;
            }
            for (PostFeedRow row : rows) {
                upsertPost(row.getId());
                indexed++;
            }
            offset += rows.size();
        }
        return indexed;
    }

    private long countPublishedDocuments() throws Exception {
        return es.count(c -> c.index(INDEX)
                .query(q -> q.term(t -> t.field("status").value("published")))).count();
    }

    /**
     * upsert 内容文档：写入基础字段、计数与补全。使用 wait_for 刷新以保障“立即可搜”。
     */
    public void upsertPost(long id) {
        try {
            upsertPostOrThrow(id, false);
        } catch (Exception e) {
            log.error("Index upsert failed for post {}: {}", id, e.getMessage(), e);
        }
    }

    /**
     * Attempts one post upsert and exposes failure to batch import callers.
     *
     * @param id post ID
     * @return {@code true} only when the post was indexed from a non-blank full body, or no longer exists
     */
    public boolean tryUpsertPost(long id) {
        try {
            upsertPostOrThrow(id, true);
            return true;
        } catch (Exception e) {
            log.error("Index upsert failed for post {}: {}", id, e.getMessage(), e);
            return false;
        }
    }

    private void upsertPostOrThrow(long id, boolean requireFullBody) throws Exception {
        PostDetailRow row = postMapper.findDetailById(id);
        if (row == null) {
            log.warn("Index upsert skipped: post {} not found", id);
            return;
        }
        Map<String, Object> doc = new HashMap<>();
        doc.put("content_id", row.getId());
        doc.put("content_type", row.getType());
        doc.put("slug", row.getSlug());
        doc.put("title", row.getTitle());
        doc.put("description", row.getDescription());
        doc.put("author_id", row.getCreatorId());
        doc.put("author_avatar", row.getAuthorAvatar());
        doc.put("author_nickname", row.getAuthorNickname());
        doc.put("author_tag_json", row.getAuthorTagJson());
        if (row.getPublishTime() != null) {
            doc.put("publish_time", row.getPublishTime().toEpochMilli());
        }
        doc.put("status", row.getStatus());
        doc.put("tags", parseStringArray(row.getTags()));
        doc.put("img_urls", parseStringArray(row.getImgUrls()));
        if (row.getIsTop() != null) {
            doc.put("is_top", row.getIsTop());
        }

        // 正文优先拉取 contentUrl，失败则使用描述
        String absoluteUrl = absoluteContentUrl(row.getContentUrl());
        String body = requireFullBody ? fetchContentStrict(absoluteUrl) : fetchContentSafe(absoluteUrl);
        if (body == null || body.isBlank()) {
            body = row.getDescription();
        }
        if (body != null) {
            doc.put("body", truncate(body, 4000));
        }

        Map<String, Long> counts = counterService.getCounts("post", String.valueOf(id), List.of("like", "fav"));
        doc.put("like_count", counts.getOrDefault("like", 0L));
        doc.put("favorite_count", counts.getOrDefault("fav", 0L));
        doc.put("view_count", 0L);

        if (row.getTitle() != null && !row.getTitle().isBlank()) {
            doc.put("title_suggest", row.getTitle());
        }

        // 刷新策略：wait_for，保证写入后即刻可检索
        IndexRequest<Map<String, Object>> req = IndexRequest.of(b -> b
                .index(INDEX)
                .id(String.valueOf(id))
                .document(doc)
                .refresh(Refresh.WaitFor)
        );
        IndexResponse resp = es.index(req);
        log.info("Indexed post {} result={} version={}", id, resp.result(), resp.version());
    }

    private String absoluteContentUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        if (url.startsWith("/")) {
            return contentBaseUrl + url;
        }
        throw new IllegalArgumentException("Unsupported content URL: " + url);
    }

    /**
     * 软删内容：仅更新 status=deleted，同一文档 ID 覆盖写入。
     */
    public void softDeletePost(long id) {
        try {
            Map<String, Object> doc = new HashMap<>();
            doc.put("content_id", id);
            doc.put("status", "deleted");
            IndexRequest<Map<String, Object>> req = IndexRequest.of(b -> b
                    .index(INDEX)
                    .id(String.valueOf(id))
                    .document(doc)
                    .refresh(Refresh.WaitFor)
            );
            es.index(req);
        } catch (Exception e) {
            log.error("Index soft delete failed for post {}: {}", id, e.getMessage());
        }
    }

    /**
     * 安全拉取正文内容：失败返回 null，不中断索引流程。
     */
    private String fetchContentSafe(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(List.of(MediaType.TEXT_HTML, MediaType.TEXT_PLAIN, MediaType.APPLICATION_JSON));
            ResponseEntity<byte[]> resp = contentRestTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
            byte[] bytes = resp.getBody();
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            MediaType contentType = resp.getHeaders().getContentType();
            Charset headerCharset = (contentType != null) ? contentType.getCharset() : null;
            Charset metaCharset = sniffHtmlCharset(bytes);
            Charset charset = pickCharset(bytes, headerCharset, metaCharset);
            return new String(bytes, charset);
        } catch (Exception e) {
            log.warn("Failed to fetch post content from url={}: {}", url, e.getMessage());
            return null;
        }
    }

    private String fetchContentStrict(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("Post content URL is missing");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.TEXT_HTML, MediaType.TEXT_PLAIN, MediaType.APPLICATION_JSON));
        ResponseEntity<byte[]> response = contentRestTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
        byte[] bytes = response.getBody();
        if (bytes == null || bytes.length == 0) {
            throw new IllegalStateException("Post content is empty: " + url);
        }
        MediaType contentType = response.getHeaders().getContentType();
        Charset headerCharset = contentType == null ? null : contentType.getCharset();
        Charset charset = pickCharset(bytes, headerCharset, sniffHtmlCharset(bytes));
        String body;
        try {
            body = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalStateException("Post content cannot be decoded as " + charset + ": " + url, exception);
        }
        if (body.isBlank()) {
            throw new IllegalStateException("Post content is blank: " + url);
        }
        return body;
    }

    private Charset pickCharset(byte[] bytes, Charset headerCharset, Charset metaCharset) {
        if (metaCharset != null) {
            return metaCharset;
        }
        if (headerCharset == null) {
            Charset utf8 = StandardCharsets.UTF_8;
            Charset gb18030 = Charset.forName("GB18030");
            return countReplacementChars(new String(bytes, utf8)) <= countReplacementChars(new String(bytes, gb18030)) ? utf8 : gb18030;
        }
        if (isLikelyWrongCharsetHeader(headerCharset)) {
            Charset utf8 = StandardCharsets.UTF_8;
            Charset gb18030 = Charset.forName("GB18030");
            int repUtf8 = countReplacementChars(new String(bytes, utf8));
            int repGb = countReplacementChars(new String(bytes, gb18030));
            int repHeader = countReplacementChars(new String(bytes, headerCharset));
            if (repUtf8 <= repGb && repUtf8 <= repHeader) return utf8;
            if (repGb <= repHeader) return gb18030;
        }
        return headerCharset;
    }

    private boolean isLikelyWrongCharsetHeader(Charset charset) {
        return StandardCharsets.ISO_8859_1.equals(charset) || StandardCharsets.US_ASCII.equals(charset);
    }

    private Charset sniffHtmlCharset(byte[] bytes) {
        int limit = Math.min(bytes.length, 8192);
        String head = new String(bytes, 0, limit, StandardCharsets.ISO_8859_1);
        Matcher m = Pattern.compile("charset\\s*=\\s*['\\\"]?([a-zA-Z0-9_\\-]+)", Pattern.CASE_INSENSITIVE).matcher(head);
        if (!m.find()) {
            return null;
        }
        String cs = m.group(1);
        if (cs == null || cs.isBlank()) {
            return null;
        }
        cs = cs.trim();
        if ("utf8".equalsIgnoreCase(cs)) {
            return StandardCharsets.UTF_8;
        }
        if ("gbk".equalsIgnoreCase(cs) || "gb2312".equalsIgnoreCase(cs) || "gb18030".equalsIgnoreCase(cs)) {
            return Charset.forName("GB18030");
        }
        try {
            return Charset.forName(cs);
        } catch (Exception e) {
            return null;
        }
    }

    private int countReplacementChars(String s) {
        if (s == null || s.isEmpty()) return 0;
        int cnt = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\uFFFD') cnt++;
        }
        return cnt;
    }

    /**
     * 截断字符串到最大长度。
     */
    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }

        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * 将 JSON 数组字符串解析为 List<String>；异常返回空列表。
     */
    private List<String> parseStringArray(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
