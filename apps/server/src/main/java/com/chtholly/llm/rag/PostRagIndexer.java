package com.chtholly.llm.rag;

/**
 * 帖子 RAG 投影门面；实现必须按 MySQL 当前状态幂等重建或移除旧切片。
 */
public interface PostRagIndexer {

    /** Reconciles all vector chunks for one post with its authoritative MySQL state. */
    void ensureIndexed(long postId);
}
