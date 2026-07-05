package com.chtholly.agent.eval;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loads evaluation dimensions and exposes prompt-friendly keys.
 *
 * @param dimensions ordered scoring dimensions
 */
public record EvaluationDimensionSet(List<EvaluationDimension> dimensions) {

    /**
     * Loads dimensions from a classpath YAML resource.
     *
     * @param resourcePath classpath resource path
     * @return parsed dimension set
     */
    @SuppressWarnings("unchecked")
    public static EvaluationDimensionSet loadClasspath(String resourcePath) {
        try (InputStream input = classLoader().getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IllegalArgumentException("Missing eval dimension resource: " + resourcePath);
            }
            Map<String, Object> root = new Yaml().load(input);
            List<Map<String, Object>> rawDimensions = (List<Map<String, Object>>) root.getOrDefault("dimensions", List.of());
            List<EvaluationDimension> dimensions = new ArrayList<>(rawDimensions.size());
            for (Map<String, Object> raw : rawDimensions) {
                dimensions.add(new EvaluationDimension(
                        string(raw, "key"),
                        string(raw, "name"),
                        string(raw, "description"),
                        (Map<String, String>) raw.getOrDefault("rubric", Map.of())));
            }
            return new EvaluationDimensionSet(List.copyOf(dimensions));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load eval dimensions from " + resourcePath, e);
        }
    }

    public List<String> keys() {
        return dimensions.stream().map(EvaluationDimension::key).toList();
    }

    private static ClassLoader classLoader() {
        return Thread.currentThread().getContextClassLoader();
    }

    private static String string(Map<String, Object> raw, String key) {
        Object value = raw.get(key);
        return value == null ? "" : value.toString();
    }
}
