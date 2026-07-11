package com.chtholly.agent.memory;

import com.chtholly.agent.learning.InsightService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProceduralMemoryServiceTest {

    private final InsightService insightService = mock(InsightService.class);
    private final ProceduralMemoryService service = new ProceduralMemoryService(insightService);

    @Test
    void delegatesStorageAndRetrievalToCanonicalInsightStore() {
        when(insightService.getTopRules(7L, 5, 500)).thenReturn(List.of("先确认作品名"));

        service.storeRule(7L, "先确认作品名");

        verify(insightService).storeRule(7L, "先确认作品名");
        assertThat(service.getTopRules(7L, 5, 500)).containsExactly("先确认作品名");
    }

    @Test
    void delegatesUsageAndNegativeFeedback() {
        service.recordRuleUsage(7L, "rule-1");
        service.recordNegativeFeedback(7L, "rule-1");

        verify(insightService).recordRuleUsage(7L, "rule-1");
        verify(insightService).recordNegativeFeedback(7L, "rule-1");
    }
}
