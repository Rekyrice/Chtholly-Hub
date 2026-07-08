package com.chtholly.seed;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory semantic deduplicator for one seed generation run.
 *
 * <p>It degrades to "always distinct" when no {@link EmbeddingModel} is configured,
 * so local seed bootstrap does not depend on external embedding providers.
 */
@Slf4j
@Component
public class SeedPostEmbeddingDeduplicator {

    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final List<float[]> vectors = new CopyOnWriteArrayList<>();

    public SeedPostEmbeddingDeduplicator(ObjectProvider<EmbeddingModel> embeddingModelProvider) {
        this.embeddingModelProvider = embeddingModelProvider;
    }

    /**
     * Stores the post vector only when it is sufficiently different from previous seed posts.
     *
     * @param text seed post body
     * @param similarityThreshold duplicate threshold, usually 0.75
     * @return true when the post can be used, false when it is too similar
     */
    public boolean rememberIfDistinct(String text, double similarityThreshold) {
        EmbeddingModel embeddingModel = embeddingModelProvider == null ? null : embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null || !StringUtils.hasText(text)) {
            return true;
        }

        float[] vector = embed(embeddingModel, text);
        if (vector.length == 0) {
            return true;
        }
        for (float[] existing : vectors) {
            if (cosine(existing, vector) > similarityThreshold) {
                return false;
            }
        }
        vectors.add(vector);
        return true;
    }

    private float[] embed(EmbeddingModel embeddingModel, String text) {
        try {
            float[] vector = embeddingModel.embed(text);
            return vector == null ? new float[0] : vector;
        } catch (Exception e) {
            log.warn("Seed post embedding failed, skipping deduplication: {}", e.getMessage());
            return new float[0];
        }
    }

    private static double cosine(float[] left, float[] right) {
        if (left.length == 0 || right.length == 0 || left.length != right.length) {
            return 0.0;
        }
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}
