package com.chtholly.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

/** Writes one counter correctness result to a new repository-local ignored run directory. */
final class CounterEvidenceResultWriter {

    private final ObjectMapper objectMapper;

    CounterEvidenceResultWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void write(Instant startedAt, Metrics metrics, CalibratedCounts counts,
               String mysqlImage, String redisImage, String kafkaImage) throws Exception {
        String runId = requiredProperty("counter.evidence.run-id");
        if (!runId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")) {
            throw new IllegalArgumentException("Counter evidence run ID is invalid");
        }
        Path repoRoot = Path.of(requiredProperty("counter.evidence.repo-root")).toRealPath();
        Path resultsRoot = repoRoot.resolve(".benchmark-results");
        Files.createDirectories(resultsRoot);
        Path realResultsRoot = resultsRoot.toRealPath();
        if (!realResultsRoot.startsWith(repoRoot)) {
            throw new IllegalStateException("Counter evidence results root escapes the repository");
        }
        Path runDirectory = realResultsRoot.resolve(runId);
        Files.createDirectory(runDirectory);

        ObjectNode result = objectMapper.createObjectNode();
        result.put("schemaVersion", 1).put("runId", runId).put("status", "COMPLETED")
                .put("operationSequence", "counter-interaction-v1")
                .put("subjectCommit", commitProperty("counter.evidence.subject-commit"))
                .put("harnessCommit", commitProperty("counter.evidence.harness-commit"))
                .put("datasetCommit", commitProperty("counter.evidence.dataset-commit"))
                .put("startedAt", startedAt.toString()).put("endedAt", Instant.now().toString());
        result.putObject("metrics").put("requestTotal", metrics.requestTotal())
                .put("stateChangeCount", metrics.stateChangeCount())
                .put("kafkaEventCount", metrics.kafkaEventCount()).put("dedupHitCount", metrics.dedupHitCount())
                .put("aggregationBatchCount", metrics.aggregationBatchCount())
                .put("mysqlUpdateCount", metrics.mysqlUpdateCount())
                .put("preCalibrationDiscrepancy", metrics.preCalibrationDiscrepancy())
                .put("postCalibrationDiscrepancy", metrics.postCalibrationDiscrepancy());
        result.putObject("environment").put("mysqlImage", mysqlImage).put("redisImage", redisImage)
                .put("kafkaImage", kafkaImage).put("javaVersion", System.getProperty("java.version"))
                .put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        result.putObject("calibratedCounts").put("bitmapLike", counts.bitmapLike())
                .put("redisLike", counts.redisLike()).put("mysqlLike", counts.mysqlLike())
                .put("bitmapFav", counts.bitmapFav()).put("redisFav", counts.redisFav())
                .put("mysqlFav", counts.mysqlFav()).put("factEpoch", counts.factEpoch());

        Path temporary = runDirectory.resolve("counter-evidence.json.tmp");
        Path output = runDirectory.resolve("counter-evidence.json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), result);
        try {
            Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, output);
        }
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) { throw new IllegalArgumentException(name + " is required"); }
        return value;
    }

    private static String commitProperty(String name) {
        String value = requiredProperty(name);
        if (!value.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException(name + " must be a full commit");
        }
        return value;
    }

    record Metrics(int requestTotal, long stateChangeCount, int kafkaEventCount, int dedupHitCount,
                   int aggregationBatchCount, int mysqlUpdateCount, long preCalibrationDiscrepancy,
                   long postCalibrationDiscrepancy) { }

    record CalibratedCounts(long bitmapLike, long redisLike, long mysqlLike,
                            long bitmapFav, long redisFav, long mysqlFav, long factEpoch) { }
}
