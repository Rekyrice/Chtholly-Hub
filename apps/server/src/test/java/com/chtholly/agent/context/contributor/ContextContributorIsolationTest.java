package com.chtholly.agent.context.contributor;

import com.chtholly.agent.anchor.AnchorContext;
import com.chtholly.agent.anchor.KnowledgeService;
import com.chtholly.agent.context.ContextContribution;
import com.chtholly.agent.context.ContextRequest;
import com.chtholly.agent.graph.KnowledgeGraphService;
import com.chtholly.agent.mood.SeasonService;
import com.chtholly.agent.search.HybridSearchService;
import com.chtholly.agent.search.SearchResult;
import com.chtholly.agent.state.CharacterStateService;
import com.chtholly.agent.state.EmotionState;
import com.chtholly.content.ContentIntelligenceReader;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContextContributorIsolationTest {

    @Test
    void seasonalFailureKeepsRelationshipSection() {
        CharacterStateService stateService = stateService();
        SeasonService seasonService = mock(SeasonService.class);
        when(seasonService.getSeasonalPrompt()).thenThrow(new IllegalStateException("season unavailable"));

        ContextContribution contribution = new RelationshipContextContributor(stateService, seasonService)
                .contribute(request("", "继续聊", AnchorContext.builder().build()));

        assertThat(contribution.content()).contains("## 当前状态");
        assertThat(contribution.degraded()).isTrue();
    }

    @Test
    void relationshipStateFailureKeepsSeasonalSection() {
        CharacterStateService stateService = mock(CharacterStateService.class);
        when(stateService.getMoodBaseline()).thenThrow(new IllegalStateException("state unavailable"));
        SeasonService seasonService = mock(SeasonService.class);
        when(seasonService.getSeasonalPrompt()).thenReturn("秋天了……适合感性一点的故事。");

        ContextContribution contribution = new RelationshipContextContributor(stateService, seasonService)
                .contribute(request("", "继续聊", AnchorContext.builder().build()));

        assertThat(contribution.content())
                .contains("## 季节感受", "秋天了……适合感性一点的故事。")
                .doesNotContain("## 当前状态");
        assertThat(contribution.degraded()).isTrue();
    }

    @Test
    void contentReaderFailureKeepsPlainPageSection() {
        ContentIntelligenceReader contentReader = mock(ContentIntelligenceReader.class);
        when(contentReader.getAnalysis(42L)).thenThrow(new IllegalStateException("content unavailable"));

        ContextContribution contribution = new PageContextContributor(contentReader)
                .contribute(request("postId: 42\n标题：时间", "怎么看", AnchorContext.builder().build()));

        assertThat(contribution.content())
                .contains("## 用户当前在看")
                .contains("postId: 42");
        assertThat(contribution.degraded()).isTrue();
    }

    @Test
    void oneKnowledgeSourceFailureKeepsOtherKnowledgeSources() {
        KnowledgeGraphService graphService = mock(KnowledgeGraphService.class);
        KnowledgeService knowledgeService = mock(KnowledgeService.class);
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        String question = "帮我查芙莉莲这部动漫";
        when(graphService.contextForQuestion(question, 5))
                .thenThrow(new IllegalStateException("graph unavailable"));
        when(knowledgeService.searchRelevantKnowledge(question, 3)).thenReturn(List.of("known fact"));
        when(hybridSearchService.hybridSearch(question, 5)).thenReturn(List.of(
                new SearchResult("post:1", "result title", "result snippet", "hybrid", 0.8)));
        AnchorContext anchors = AnchorContext.builder().semantic(List.of("anchor semantic")).build();

        ContextContribution contribution = new KnowledgeContextContributor(
                hybridSearchService, knowledgeService, graphService)
                .contribute(request("", question, anchors));

        assertThat(contribution.content())
                .contains("## 你知道的事", "known fact")
                .contains("## 相关知识", "anchor semantic", "result title：result snippet");
        assertThat(contribution.degraded()).isTrue();
    }

    private CharacterStateService stateService() {
        CharacterStateService stateService = mock(CharacterStateService.class);
        when(stateService.getMoodBaseline()).thenReturn(0.0);
        when(stateService.getMoodValence()).thenReturn(0.0);
        when(stateService.getCurrentEmotion()).thenReturn(new EmotionState(
                "平静", 0.2, Instant.EPOCH, "test"));
        return stateService;
    }

    private ContextRequest request(String pageContext, String question, AnchorContext anchors) {
        return new ContextRequest(7L, "session-1", pageContext, List.of(), "", question, anchors);
    }
}
