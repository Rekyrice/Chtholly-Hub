package com.chtholly.search.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.CompletionProperty;
import co.elastic.clients.elasticsearch._types.mapping.DateProperty;
import co.elastic.clients.elasticsearch._types.mapping.IntegerNumberProperty;
import co.elastic.clients.elasticsearch._types.mapping.KeywordProperty;
import co.elastic.clients.elasticsearch._types.mapping.LongNumberProperty;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TextProperty;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

/**
 * 搜索索引初始化：应用启动时确保索引与 Mapping 存在。
 * 优先使用 IK 分词；本地 ES 未装 analysis-ik 时回退 standard。
 */
@Service
@RequiredArgsConstructor
public class SearchIndexInitializer {
    private static final Logger log = LoggerFactory.getLogger(SearchIndexInitializer.class);
    private static final String INDEX = "chtholly_content_index";

    private final ElasticsearchClient es;

    @PostConstruct
    public void ensureIndex() {
        try {
            if (es.indices().exists(e -> e.index(INDEX)).value()) {
                return;
            }
            try {
                es.indices().create(c -> applyMappings(c, "ik_max_word", "ik_smart", "ik_max_word"));
                log.info("Search index {} created with IK analyzers", INDEX);
            } catch (Exception ikError) {
                log.warn("IK analyzer unavailable, fallback to standard: {}", ikError.getMessage());
                es.indices().create(c -> applyMappings(c, "standard", "standard", "standard"));
                log.info("Search index {} created with standard analyzer", INDEX);
            }
        } catch (Exception e) {
            log.warn("Search index init failed: {}", e.getMessage());
        }
    }

    private co.elastic.clients.elasticsearch.indices.CreateIndexRequest.Builder applyMappings(
            co.elastic.clients.elasticsearch.indices.CreateIndexRequest.Builder c,
            String titleAnalyzer,
            String titleSearchAnalyzer,
            String bodyAnalyzer
    ) {
        return c.index(INDEX)
                .settings(s -> s.numberOfReplicas("0"))
                .mappings(m -> m
                .properties("content_id", Property.of(p -> p.long_(LongNumberProperty.of(b -> b))))
                .properties("content_type", Property.of(p -> p.keyword(KeywordProperty.of(b -> b))))
                .properties("slug", Property.of(p -> p.keyword(KeywordProperty.of(b -> b))))
                .properties("description", Property.of(p -> p.text(TextProperty.of(b -> b.analyzer(bodyAnalyzer)))))
                .properties("title", Property.of(p -> p.text(TextProperty.of(b -> b
                        .analyzer(titleAnalyzer)
                        .searchAnalyzer(titleSearchAnalyzer)))))
                .properties("body", Property.of(p -> p.text(TextProperty.of(b -> b.analyzer(bodyAnalyzer)))))
                .properties("tags", Property.of(p -> p.keyword(KeywordProperty.of(b -> b))))
                .properties("author_id", Property.of(p -> p.long_(LongNumberProperty.of(b -> b))))
                .properties("author_avatar", Property.of(p -> p.keyword(KeywordProperty.of(b -> b))))
                .properties("author_nickname", Property.of(p -> p.keyword(KeywordProperty.of(b -> b))))
                .properties("author_tag_json", Property.of(p -> p.keyword(KeywordProperty.of(b -> b))))
                .properties("publish_time", Property.of(p -> p.date(DateProperty.of(b -> b))))
                .properties("like_count", Property.of(p -> p.integer(IntegerNumberProperty.of(b -> b))))
                .properties("favorite_count", Property.of(p -> p.integer(IntegerNumberProperty.of(b -> b))))
                .properties("view_count", Property.of(p -> p.integer(IntegerNumberProperty.of(b -> b))))
                .properties("status", Property.of(p -> p.keyword(KeywordProperty.of(b -> b))))
                .properties("img_urls", Property.of(p -> p.keyword(KeywordProperty.of(b -> b))))
                .properties("is_top", Property.of(p -> p.boolean_(b -> b)))
                .properties("title_suggest", Property.of(p -> p.completion(CompletionProperty.of(b -> b))))
        );
    }
}
