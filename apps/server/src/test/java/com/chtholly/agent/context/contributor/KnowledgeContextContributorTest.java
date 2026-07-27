package com.chtholly.agent.context.contributor;

import com.chtholly.agent.anchor.AnchorContext;
import com.chtholly.agent.context.ContextContribution;
import com.chtholly.agent.context.ContextRequest;
import com.chtholly.agent.search.HybridSearchService;
import com.chtholly.agent.search.SearchResult;
import com.chtholly.agent.skill.EvidencePolicy;
import com.chtholly.llm.rag.RagQueryService;
import com.chtholly.post.mapper.PostMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeContextContributorTest {

    @Test
    void heuristicRetrievalDoesNotMakeOrdinaryToolQuestionCitationMandatory() {
        HybridSearchService search = mock(HybridSearchService.class);
        when(search.hybridSearch("查询《迷宫饭》的评分、集数和角色", 5))
                .thenReturn(new HybridSearchService.HybridSearchResponse(
                        List.of(),
                        Map.of("semantic", HybridSearchService.RetrievalStatus.SUCCESS_EMPTY)));
        KnowledgeContextContributor contributor = new KnowledgeContextContributor(search, null);

        ContextContribution contribution = contributor.contribute(request(
                "查询《迷宫饭》的评分、集数和角色", EvidencePolicy.NOT_NEEDED));

        assertThat(contribution.evidenceRequired()).isFalse();
        assertThat(contribution.retrievalStatuses()).containsEntry("semantic", "SUCCESS_EMPTY");
        verify(search).hybridSearch("查询《迷宫饭》的评分、集数和角色", 5);
    }

    @Test
    void requiredEvidencePolicyStillMakesEvidenceMandatory() {
        HybridSearchService search = mock(HybridSearchService.class);
        when(search.hybridSearch("京都动画如何表现人物关系", 5))
                .thenReturn(new HybridSearchService.HybridSearchResponse(
                        List.of(),
                        Map.of("semantic", HybridSearchService.RetrievalStatus.SUCCESS_EMPTY)));
        KnowledgeContextContributor contributor = new KnowledgeContextContributor(search, null);

        ContextContribution contribution = contributor.contribute(request(
                "生成证据大纲", EvidencePolicy.REQUIRED, "京都动画如何表现人物关系"));

        assertThat(contribution.evidenceRequired()).isTrue();
    }

    @Test
    void requiredCurrentPostRequestUsesOnlyPostScopedRagEvidence() {
        HybridSearchService hybridSearch = mock(HybridSearchService.class);
        RagQueryService ragQueryService = mock(RagQueryService.class);
        PostMapper postMapper = mock(PostMapper.class);
        when(postMapper.findIdBySlug("dungeon-meshi")).thenReturn(42L);
        when(ragQueryService.searchPost(42L, "总结三个主要观点", 5))
                .thenReturn(List.of(searchResult("post:42", "42#0", "正文证据")));
        KnowledgeContextContributor contributor = new KnowledgeContextContributor(
                hybridSearch, null, ragQueryService, postMapper);
        ContextRequest request = new ContextRequest(
                1L,
                "session",
                "source: post:dungeon-meshi",
                List.of(),
                "",
                "只依据当前文章总结三个主要观点",
                AnchorContext.builder().build(),
                EvidencePolicy.REQUIRED,
                "总结三个主要观点");

        ContextContribution contribution = contributor.contribute(request);

        assertThat(contribution.evidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.sourceId()).isEqualTo("post:42");
            assertThat(evidence.chunkId()).isEqualTo("42#0");
        });
        assertThat(contribution.retrievalStatuses())
                .containsEntry("current_post", "SUCCESS_RESULTS");
        verifyNoInteractions(hybridSearch);
    }

    private ContextRequest request(String question, EvidencePolicy policy) {
        return request(question, policy, "");
    }

    private ContextRequest request(String question, EvidencePolicy policy, String retrievalQuery) {
        return new ContextRequest(
                1L,
                "session",
                "",
                List.of(),
                "",
                question,
                AnchorContext.builder().build(),
                policy,
                retrievalQuery);
    }

    private SearchResult searchResult(String id, String chunkId, String snippet) {
        return new SearchResult(
                id,
                "迷宫饭文章",
                snippet,
                "semantic",
                0.9,
                id,
                chunkId,
                "current",
                "sha-42",
                java.util.Set.of("PUBLIC"));
    }
}
