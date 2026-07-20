package com.chtholly.agent.search;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Local deterministic hash embedding used only for pipeline correctness tests. */
final class DeterministicTestEmbeddingModel implements EmbeddingModel {

    static final int DIMENSIONS = 64;

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> embeddings = new ArrayList<>();
        List<String> instructions = request.getInstructions();
        for (int index = 0; index < instructions.size(); index++) {
            embeddings.add(new Embedding(vector(instructions.get(index)), index));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return vector(document == null ? "" : document.getText());
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    private float[] vector(String input) {
        String normalized = input == null ? "" : input.toLowerCase(Locale.ROOT).trim();
        float[] values = new float[DIMENSIONS];
        normalized.lines()
                .flatMap(line -> List.of(line.split("\\s+")).stream())
                .filter(token -> !token.isBlank())
                .forEach(token -> add(values, token));
        int[] codePoints = normalized.codePoints().toArray();
        for (int index = 0; index < codePoints.length - 1; index++) {
            add(values, new String(codePoints, index, 2));
        }
        normalize(values);
        return values;
    }

    private void add(float[] values, String token) {
        int hash = token.hashCode();
        int index = Math.floorMod(hash, values.length);
        values[index] += ((hash >>> 8) & 1) == 0 ? 1.0f : -1.0f;
    }

    private void normalize(float[] values) {
        double squared = 0.0;
        for (float value : values) {
            squared += value * value;
        }
        if (squared == 0.0) {
            values[0] = 1.0f;
            return;
        }
        double norm = Math.sqrt(squared);
        for (int index = 0; index < values.length; index++) {
            values[index] = (float) (values[index] / norm);
        }
    }
}
