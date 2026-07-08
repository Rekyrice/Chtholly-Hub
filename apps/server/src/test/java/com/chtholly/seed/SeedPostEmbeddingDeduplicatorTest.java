package com.chtholly.seed;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SeedPostEmbeddingDeduplicatorTest {

    @Test
    void remembersFirstPostAndRejectsSemanticallySimilarPost() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed("第一篇文章")).thenReturn(new float[]{1.0f, 0.0f, 0.0f});
        when(embeddingModel.embed("相似文章")).thenReturn(new float[]{0.9f, 0.1f, 0.0f});
        SeedPostEmbeddingDeduplicator deduplicator = new SeedPostEmbeddingDeduplicator(provider(embeddingModel));

        assertThat(deduplicator.rememberIfDistinct("第一篇文章", 0.75)).isTrue();
        assertThat(deduplicator.rememberIfDistinct("相似文章", 0.75)).isFalse();
    }

    @Test
    void skipsDeduplicationWhenEmbeddingModelIsUnavailable() {
        SeedPostEmbeddingDeduplicator deduplicator = new SeedPostEmbeddingDeduplicator(provider(null));

        assertThat(deduplicator.rememberIfDistinct("第一篇文章", 0.75)).isTrue();
        assertThat(deduplicator.rememberIfDistinct("相似文章", 0.75)).isTrue();
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
