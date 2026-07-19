package com.chtholly.agent.search;

import com.chtholly.bangumi.model.BangumiSubjectRow;
import com.chtholly.bangumi.service.BangumiService;
import com.chtholly.config.EsProperties;
import org.elasticsearch.client.RestClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStoreOptions;
import org.springframework.ai.vectorstore.elasticsearch.SimilarityFunction;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.List;

@TestConfiguration(proxyBeanMethods = false)
class RetrievalInfrastructureTestConfig {

    static final String VECTOR_INDEX = "chtholly-ai-retrieval-it";

    @Bean(name = "deepSeekChatModel")
    CountingInertChatModel deepSeekChatModel() {
        return new CountingInertChatModel();
    }

    @Bean
    EmbeddingModel deterministicEmbeddingModel() {
        return new DeterministicTestEmbeddingModel();
    }

    @Bean(destroyMethod = "close")
    RestClient retrievalVectorRestClient(EsProperties properties) {
        return RestClient.builder(org.apache.http.HttpHost.create(properties.getHost())).build();
    }

    @Bean
    VectorStore retrievalVectorStore(
            @Qualifier("retrievalVectorRestClient") RestClient restClient,
            EmbeddingModel embeddingModel) {
        ElasticsearchVectorStoreOptions options = new ElasticsearchVectorStoreOptions();
        options.setIndexName(VECTOR_INDEX);
        options.setDimensions(DeterministicTestEmbeddingModel.DIMENSIONS);
        options.setSimilarity(SimilarityFunction.cosine);
        return ElasticsearchVectorStore.builder(restClient, embeddingModel)
                .options(options)
                .initializeSchema(true)
                .build();
    }

    @Bean
    BangumiService deterministicBangumiService() {
        return new BangumiService() {
            @Override
            public List<BangumiSubjectRow> search(String keyword, int limit) {
                BangumiSubjectRow row = new BangumiSubjectRow();
                row.setId(7001L);
                row.setName("atomic-recovery");
                row.setNameCn("原子恢复");
                return List.of(row);
            }

            @Override
            public List<BangumiSubjectRow> searchAnimeSeries(String keyword, int limit) {
                return List.of();
            }

            @Override
            public String describePersonWorks(String keyword, String workTitleHint, String workType) {
                return "";
            }

            @Override
            public String describeSubjectCharacters(String keyword) {
                return "";
            }
        };
    }
}
