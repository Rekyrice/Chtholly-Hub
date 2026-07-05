package com.chtholly.agent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;

/**
 * Persists evaluation reports as JSON files and attaches previous-run comparison.
 */
public class EvalResultStore {

    private final Path directory;
    private final ObjectMapper objectMapper;

    public EvalResultStore() {
        this(Path.of("eval-results"), new ObjectMapper().findAndRegisterModules());
    }

    public EvalResultStore(Path directory, ObjectMapper objectMapper) {
        this.directory = directory;
        this.objectMapper = objectMapper;
    }

    /**
     * Writes the report and returns a copy enriched with previous-run comparison.
     *
     * @param report report to store
     * @return stored report with comparison fields
     */
    public EvaluationReport write(EvaluationReport report) {
        try {
            Files.createDirectories(directory);
            Double previous = previousReport()
                    .map(EvaluationReport::overallScore)
                    .orElse(null);
            EvaluationReport enriched = report.withComparison(previous);
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(directory.resolve(report.runId() + ".json").toFile(), enriched);
            return enriched;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write eval report", e);
        }
    }

    private Optional<EvaluationReport> previousReport() throws IOException {
        if (!Files.exists(directory)) {
            return Optional.empty();
        }
        try (var stream = Files.list(directory)) {
            return stream
                    .filter(path -> path.toString().endsWith(".json"))
                    .max(Comparator.comparingLong(this::lastModified))
                    .map(this::read);
        }
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private EvaluationReport read(Path path) {
        try {
            return objectMapper.readValue(path.toFile(), EvaluationReport.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read previous eval report: " + path, e);
        }
    }
}
