package com.chtholly.agent.graph;

import com.chtholly.agent.config.AgentExtensionComponent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.KeywordProperty;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TextProperty;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Ensures the Elasticsearch index used for graph entity lookup exists.
 */
@Slf4j
@Service
@AgentExtensionComponent
@ConditionalOnProperty(prefix = "agent.extensions.graph", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeGraphIndexInitializer {

    public static final String INDEX = "knowledge_entities_index";

    private final ObjectProvider<ElasticsearchClient> esClientProvider;

    public KnowledgeGraphIndexInitializer(ObjectProvider<ElasticsearchClient> esClientProvider) {
        this.esClientProvider = esClientProvider;
    }

    @PostConstruct
    public void ensureIndex() {
        ElasticsearchClient esClient = esClientProvider.getIfAvailable();
        if (esClient == null) {
            return;
        }
        try {
            if (esClient.indices().exists(e -> e.index(INDEX)).value()) {
                return;
            }
            esClient.indices().create(c -> c
                    .index(INDEX)
                    .mappings(m -> m
                            .properties("name", Property.of(p -> p.text(TextProperty.of(t -> t
                                    .fields("keyword", Property.of(k -> k.keyword(KeywordProperty.of(b -> b))))))))
                            .properties("type", Property.of(p -> p.keyword(KeywordProperty.of(b -> b))))
                            .properties("description", Property.of(p -> p.text(TextProperty.of(b -> b))))
                            .properties("aliases", Property.of(p -> p.text(TextProperty.of(b -> b))))
                            .properties("metadata", Property.of(p -> p.keyword(KeywordProperty.of(b -> b))))));
            log.info("Knowledge graph entity index {} created", INDEX);
        } catch (Exception e) {
            log.warn("Knowledge graph index init failed: {}", e.getMessage());
        }
    }
}
