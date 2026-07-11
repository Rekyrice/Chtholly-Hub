package com.chtholly;

import com.chtholly.seed.SeedRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

@SpringBootApplication
public class ChthollyApplication {

    public static void main(String[] args) {
        if (isContentPackCli(args)) {
            runContentPackCli(args, System::exit);
            return;
        }
        SpringApplication.run(ChthollyApplication.class, args);
    }

    static boolean isContentPackCli(String[] args) {
        return Arrays.stream(args)
                .filter(argument -> argument.startsWith("--mode=") || argument.startsWith("--seed.mode="))
                .map(argument -> argument.substring(argument.indexOf('=') + 1))
                .map(raw -> raw.trim().replace('-', '_'))
                .anyMatch("content_pack"::equalsIgnoreCase);
    }

    static void runContentPackCli(String[] args, IntConsumer exitHandler) {
        runContentPackCli(
                args,
                exitHandler,
                launchArgs -> SpringApplication.run(ChthollyApplication.class, launchArgs),
                (context, generator) -> SpringApplication.exit(context, generator));
    }

    static void runContentPackCli(
            String[] args,
            IntConsumer exitHandler,
            ApplicationLauncher launcher,
            ContextExiter contextExiter) {
        String[] normalizedArgs;
        try {
            normalizedArgs = normalizeContentPackCliArgs(args);
        } catch (IllegalArgumentException exception) {
            System.err.println("Content-pack CLI argument conflict: " + exception.getMessage());
            exitHandler.accept(1);
            return;
        }
        ConfigurableApplicationContext context = launcher.run(normalizedArgs);
        int reportExitCode = context.getBean(SeedRunner.class).contentPackExitCode();
        int exitCode = contextExiter.exit(context, () -> reportExitCode);
        exitHandler.accept(exitCode);
    }

    static String[] normalizeContentPackCliArgs(String[] args) {
        if (!isContentPackCli(args)) {
            return args.clone();
        }

        boolean dryRun = Arrays.asList(args).contains("--dry-run")
                || propertyValues(args, "seed.dry-run").stream().anyMatch(Boolean::parseBoolean);
        if (!dryRun) {
            rejectValue(args, "seed.cli-read-only", "true",
                    "formal import cannot enable seed.cli-read-only");
            return args.clone();
        }

        List<String> normalized = new ArrayList<>(Arrays.asList(args));
        requireSafeValue(normalized, args, "seed.cli-read-only", "true");
        requireSafeValue(normalized, args, "spring.main.web-application-type", "none");
        requireSafeValue(normalized, args, "spring.main.lazy-initialization", "true");
        requireSafeValue(normalized, args, "kafka.enabled", "false");
        requireSafeValue(normalized, args, "canal.enabled", "false");
        requireSafeValue(normalized, args, "bangumi.enabled", "false");
        return normalized.toArray(String[]::new);
    }

    private static void requireSafeValue(List<String> normalized, String[] args, String key, String required) {
        List<String> values = propertyValues(args, key);
        if (values.stream().anyMatch(value -> !required.equalsIgnoreCase(value))) {
            throw new IllegalArgumentException(key + " must be " + required + " for content-pack dry-run");
        }
        if (values.isEmpty()) {
            normalized.add("--" + key + "=" + required);
        }
    }

    private static void rejectValue(String[] args, String key, String rejected, String message) {
        if (propertyValues(args, key).stream().anyMatch(rejected::equalsIgnoreCase)) {
            throw new IllegalArgumentException(message);
        }
    }

    private static List<String> propertyValues(String[] args, String key) {
        String prefix = "--" + key + "=";
        return Arrays.stream(args)
                .filter(argument -> argument.startsWith(prefix))
                .map(argument -> argument.substring(prefix.length()).trim())
                .toList();
    }

    @FunctionalInterface
    interface ApplicationLauncher {
        ConfigurableApplicationContext run(String[] args);
    }

    @FunctionalInterface
    interface ContextExiter {
        int exit(ConfigurableApplicationContext context, org.springframework.boot.ExitCodeGenerator generator);
    }
}
