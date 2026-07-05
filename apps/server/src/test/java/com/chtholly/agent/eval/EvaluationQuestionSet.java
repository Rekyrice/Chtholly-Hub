package com.chtholly.agent.eval;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loads the YAML-backed evaluation question bank.
 *
 * @param questions ordered evaluation questions
 */
public record EvaluationQuestionSet(List<EvaluationQuestion> questions) {

    /**
     * Loads questions from a classpath YAML resource.
     *
     * @param resourcePath classpath resource path
     * @return parsed question set
     */
    @SuppressWarnings("unchecked")
    public static EvaluationQuestionSet loadClasspath(String resourcePath) {
        try (InputStream input = classLoader().getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IllegalArgumentException("Missing eval question resource: " + resourcePath);
            }
            Map<String, Object> root = new Yaml().load(input);
            List<Map<String, Object>> rawQuestions = (List<Map<String, Object>>) root.getOrDefault("questions", List.of());
            List<EvaluationQuestion> questions = new ArrayList<>(rawQuestions.size());
            for (Map<String, Object> raw : rawQuestions) {
                questions.add(new EvaluationQuestion(
                        string(raw, "id"),
                        string(raw, "category"),
                        string(raw, "text"),
                        string(raw, "userProfile"),
                        string(raw, "historySummary")));
            }
            return new EvaluationQuestionSet(List.copyOf(questions));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load eval questions from " + resourcePath, e);
        }
    }

    private static ClassLoader classLoader() {
        return Thread.currentThread().getContextClassLoader();
    }

    private static String string(Map<String, Object> raw, String key) {
        Object value = raw.get(key);
        return value == null ? "" : value.toString();
    }
}
