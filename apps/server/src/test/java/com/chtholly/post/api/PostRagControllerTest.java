package com.chtholly.post.api;

import com.chtholly.llm.rag.RagIndexService;
import com.chtholly.llm.rag.RagConversationTurn;
import com.chtholly.llm.rag.RagQueryService;
import com.chtholly.post.api.dto.PostQaRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostRagControllerTest {

    @Test
    void legacyStreamEmitsExplicitDoneMarkerBeforeCompleting() {
        RagIndexService indexService = mock(RagIndexService.class);
        RagQueryService queryService = mock(RagQueryService.class);
        when(queryService.streamAnswerFlux(42L, "核心观点是什么？", 5, 1024))
                .thenReturn(Flux.just("回答片段"));
        PostRagController controller = new PostRagController(indexService, queryService);

        StepVerifier.create(controller.qaStream(42L, "核心观点是什么？", 5, 1024))
                .expectNext("回答片段")
                .expectNext("[DONE]")
                .verifyComplete();
    }

    @Test
    void postStreamEmitsNamedEventsAndForwardsConversationHistory() {
        RagIndexService indexService = mock(RagIndexService.class);
        RagQueryService queryService = mock(RagQueryService.class);
        List<RagConversationTurn> history = List.of(
                new RagConversationTurn("上一问", "上一答")
        );
        when(queryService.streamAnswerFlux(42L, "继续说说？", history, 5, 1024))
                .thenReturn(Flux.just("第一段", "第二段"));
        PostRagController controller = new PostRagController(indexService, queryService);
        PostQaRequest request = new PostQaRequest(
                "继续说说？",
                List.of(new PostQaRequest.Turn("上一问", "上一答"))
        );

        StepVerifier.create(controller.qaStream(42L, request, 5, 1024))
                .assertNext(event -> assertEvent(event, "delta", "第一段"))
                .assertNext(event -> assertEvent(event, "delta", "第二段"))
                .assertNext(event -> assertEvent(event, "done", "[DONE]"))
                .verifyComplete();

        verify(queryService).streamAnswerFlux(42L, "继续说说？", history, 5, 1024);
    }

    private static void assertEvent(ServerSentEvent<String> event, String name, String data) {
        assertThat(event.event()).isEqualTo(name);
        assertThat(event.data()).isEqualTo(data);
    }
}
