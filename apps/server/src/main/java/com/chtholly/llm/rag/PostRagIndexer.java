package com.chtholly.llm.rag;

/**
 * 帖子 RAG 索引门面；Phase A 默认使用空实现，启用 RAG 后由 {@link RagIndexService} 接管。
 */
public interface PostRagIndexer {

    void ensureIndexed(long postId);
}
