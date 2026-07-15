package com.chtholly.seed;

import com.chtholly.seed.contentpack.ContentPackImportService;
import com.chtholly.seed.contentpack.model.ContentPackImportReport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

/**
 * Command-line entry point for cold-start seed generation.
 *
 * <p>Examples:
 * {@code --seed.enabled=true --mode=full},
 * {@code --seed.enabled=true --mode=bangumi},
 * {@code --seed.enabled=true --mode=accounts --dry-run},
 * {@code --seed.enabled=true --seed.mode=content_only}.
 */
@Slf4j
@Component
public class SeedRunner implements CommandLineRunner {

    private final SeedProperties properties;
    private final SeedOrchestrator orchestrator;
    private final ContentPackImportService contentPackImportService;
    private final ObjectMapper objectMapper;
    private int contentPackExitCode;

    public SeedRunner(SeedProperties properties,
                      SeedOrchestrator orchestrator,
                      ContentPackImportService contentPackImportService,
                      ObjectMapper objectMapper) {
        this.properties = properties;
        this.orchestrator = orchestrator;
        this.contentPackImportService = contentPackImportService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(String... args) {
        boolean requestedByArgs = Arrays.stream(args)
                .anyMatch(arg -> arg.startsWith("--mode=") || arg.startsWith("--seed.mode="));
        if (!properties.isEnabled() && !requestedByArgs) {
            return;
        }

        SeedRunMode mode = SeedRunMode.from(argValue(args, "mode")
                .or(() -> argValue(args, "seed.mode"))
                .orElse(properties.getMode()));
        boolean dryRun = Arrays.asList(args).contains("--dry-run")
                || Boolean.parseBoolean(argValue(args, "seed.dry-run")
                .orElse(String.valueOf(properties.isDryRun())));

        if (mode == SeedRunMode.CONTENT_PACK) {
            runContentPack(dryRun);
            return;
        }

        SeedRunSummary summary = orchestrator.run(new SeedRunOptions(mode, dryRun));
        log.info("Seed run finished: {}", summary);
    }

    private void runContentPack(boolean dryRun) {
        Path root = Path.of(properties.getContentPackPath()).toAbsolutePath().normalize();
        ContentPackImportReport report = contentPackImportService.run(root, dryRun);
        log.info("SEED_CONTENT_PACK_REPORT={}", toJson(report));

        contentPackExitCode = exitCodeFor(report.status());
    }

    /** Returns the process exit code derived from the last content-pack report. */
    public int contentPackExitCode() {
        return contentPackExitCode;
    }

    static int exitCodeFor(String status) {
        if ("validated".equals(status) || "completed".equals(status)) {
            return 0;
        }
        return "partial".equals(status) ? 2 : 1;
    }

    private String toJson(ContentPackImportReport report) {
        try {
            return objectMapper.writeValueAsString(report);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize content-pack import report", exception);
        }
    }

    private Optional<String> argValue(String[] args, String key) {
        String prefix = "--" + key + "=";
        return Arrays.stream(args)
                .filter(arg -> arg.startsWith(prefix))
                .map(arg -> arg.substring(prefix.length()))
                .findFirst();
    }

}
