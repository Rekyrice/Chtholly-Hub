package com.chtholly.llm.rag;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * RAG 关闭时的空实现，保证核心业务（发帖/Feed）不依赖向量库与 Embedding API。
 */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpPostRagIndexer implements PostRagIndexer {

    @Override
    public void ensureIndexed(long postId) {
        // Phase A 只读博客无需向量索引
    }
}
