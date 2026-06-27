package com.chtholly.post.api;

import com.chtholly.llm.rag.RagIndexService;
import com.chtholly.llm.rag.RagQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

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
        return ragQueryService.streamAnswerFlux(id, question, topK, maxTokens);
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