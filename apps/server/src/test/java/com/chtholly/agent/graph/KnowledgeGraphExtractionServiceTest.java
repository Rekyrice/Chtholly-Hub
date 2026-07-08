package com.chtholly.agent.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class KnowledgeGraphExtractionServiceTest {

    @Test
    void ruleExtractorFindsWorksUsersTagsAndTechConcepts() {
        KnowledgeGraphExtractionService service = new KnowledgeGraphExtractionService(
                new ObjectMapper().findAndRegisterModules(),
                noChatClient());

        KnowledgeExtractionResult result = service.extract("""
                今天重看《葬送的芙莉莲》，@Rekyrice 提到它和 RAG、Elasticsearch 的记忆检索有点像。
                #治愈 #时间
                """);

        assertThat(result.entities())
                .extracting(ExtractedKnowledgeEntity::name)
                .contains("葬送的芙莉莲", "Rekyrice", "RAG", "Elasticsearch", "治愈", "时间");
        assertThat(result.entities())
                .filteredOn(entity -> entity.name().equals("葬送的芙莉莲"))
                .extracting(ExtractedKnowledgeEntity::type)
                .containsExactly(KnowledgeEntityType.WORK);
        assertThat(result.relations())
                .extracting(ExtractedKnowledgeRelation::relationType)
                .contains(KnowledgeRelationType.RELATED_TO);
    }

    @Test
    void extractionDeduplicatesSameEntityNameAndType() {
        KnowledgeGraphExtractionService service = new KnowledgeGraphExtractionService(
                new ObjectMapper().findAndRegisterModules(),
                noChatClient());

        KnowledgeExtractionResult result = service.extract("《葬送的芙莉莲》和《葬送的芙莉莲》都在聊时间。#时间 #时间");

        assertThat(result.entities())
                .filteredOn(entity -> entity.name().equals("葬送的芙莉莲"))
                .hasSize(1);
        assertThat(result.entities())
                .filteredOn(entity -> entity.name().equals("时间"))
                .hasSize(1);
    }

    @Test
    void emptyTextReturnsEmptyExtractionResult() {
        KnowledgeGraphExtractionService service = new KnowledgeGraphExtractionService(
                new ObjectMapper().findAndRegisterModules(),
                noChatClient());

        KnowledgeExtractionResult result = service.extract("  ");

        assertThat(result.entities()).isEmpty();
        assertThat(result.relations()).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<ChatClient> noChatClient() {
        return mock(ObjectProvider.class);
    }
}
