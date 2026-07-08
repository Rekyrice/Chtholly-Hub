package com.chtholly.seed;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

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
@RequiredArgsConstructor
public class SeedRunner implements CommandLineRunner {

    private final SeedProperties properties;
    private final SeedOrchestrator orchestrator;

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
                || Boolean.parseBoolean(argValue(args, "seed.dry-run").orElse(String.valueOf(properties.isDryRun())));

        SeedRunSummary summary = orchestrator.run(new SeedRunOptions(mode, dryRun));
        log.info("Seed run finished: {}", summary);
    }

    private Optional<String> argValue(String[] args, String key) {
        String prefix = "--" + key + "=";
        return Arrays.stream(args)
                .filter(arg -> arg.startsWith(prefix))
                .map(arg -> arg.substring(prefix.length()))
                .findFirst();
    }
}
