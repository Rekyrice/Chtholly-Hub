package com.chtholly.post.api;

import com.chtholly.llm.rag.RagIndexService;
import com.chtholly.llm.rag.RagConversationTurn;
import com.chtholly.llm.rag.RagQueryService;
import com.chtholly.post.api.dto.PostQaRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * LLM-backed RAG endpoints for per-post Q&amp;A and vector index maintenance.
 */
@RestController
@RequestMapping("/api/v1/posts")
@Validated
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
@RequiredArgsConstructor
public class PostRagController {

    private final RagIndexService indexService;
    private final RagQueryService ragQueryService;

    /**
     * Streams a RAG answer for a single post as Server-Sent Events.
     *
     * @param id post snowflake ID
     * @param question user question text
     * @param topK number of retrieved chunks to include
     * @param maxTokens maximum tokens for the generated answer
     * @return SSE stream of answer text fragments
     */
    @GetMapping(value = "/{id}/qa/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> qaStream(@PathVariable("id") long id,
                                 @RequestParam("question") String question,
                                 @RequestParam(value = "topK", defaultValue = "5") int topK,
                                 @RequestParam(value = "maxTokens", defaultValue = "1024") int maxTokens) {
        return ragQueryService.streamAnswerFlux(id, question, topK, maxTokens)
                .concatWithValues("[DONE]");
    }

    /**
     * Streams a multi-turn RAG answer as named Server-Sent Events.
     *
     * @param id post snowflake ID
     * @param request current question and completed local turns
     * @param topK number of retrieved chunks to include
     * @param maxTokens maximum tokens for the generated answer
     * @return delta events followed by one explicit done event
     */
    @PostMapping(value = "/{id}/qa/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> qaStream(@PathVariable("id") long id,
                                                  @Valid @RequestBody PostQaRequest request,
                                                  @RequestParam(value = "topK", defaultValue = "5") int topK,
                                                  @RequestParam(value = "maxTokens", defaultValue = "1024") int maxTokens) {
        List<RagConversationTurn> history = request.history().stream()
                .map(turn -> new RagConversationTurn(turn.question(), turn.answer()))
                .toList();
        Flux<String> answer = ragQueryService.streamAnswerFlux(
                id, request.question(), history, topK, maxTokens);
        return toServerSentEvents(answer);
    }

    private static Flux<ServerSentEvent<String>> toServerSentEvents(Flux<String> answer) {
        return answer
                .map(chunk -> ServerSentEvent.<String>builder()
                        .event("delta")
                        .data(chunk)
                        .build())
                .concatWithValues(ServerSentEvent.<String>builder()
                        .event("done")
                        .data("[DONE]")
                        .build());
    }

    /**
     * Manually rebuilds the vector index for one post.
     *
     * @param id post snowflake ID
     * @return number of index chunks written
     */
    @PostMapping("/{id}/rag/reindex")
    public int reindex(@PathVariable("id") long id) {
        return indexService.reindexSinglePost(id);
    }
}
