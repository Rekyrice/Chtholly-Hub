package com.chtholly.llm.rag;

import java.util.function.IntSupplier;

/**
 * Serializes all derived-index mutations for one post across application nodes.
 */
@FunctionalInterface
public interface RagPostMutationLock {

    /**
     * Executes one RAG mutation while holding the post-scoped lock.
     *
     * @param postId authoritative post ID
     * @param mutation mutation to execute
     * @return mutation result
     */
    int withLock(long postId, IntSupplier mutation);
}
