package com.chtholly;

import com.chtholly.seed.SeedRunner;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class ChthollyApplicationTest {

    @Test
    void contentPackCliDetection_doesNotCaptureLegacyModesOrNormalServer() {
        assertThat(ChthollyApplication.isContentPackCli(new String[]{"--seed.mode=content_pack"})).isTrue();
        assertThat(ChthollyApplication.isContentPackCli(new String[]{"--mode=content-pack"})).isTrue();
        assertThat(ChthollyApplication.isContentPackCli(new String[]{"--mode=full"})).isFalse();
        assertThat(ChthollyApplication.isContentPackCli(new String[]{"--mode=unknown"})).isFalse();
        assertThat(ChthollyApplication.isContentPackCli(new String[]{})).isFalse();
    }

    @Test
    void runContentPackCli_waitsForRunnerThenClosesOnceAndPropagatesExitCode() {
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        SeedRunner seedRunner = mock(SeedRunner.class);
        when(context.getBean(SeedRunner.class)).thenReturn(seedRunner);
        when(seedRunner.contentPackExitCode()).thenReturn(2);
        List<String> lifecycle = new ArrayList<>();
        AtomicInteger handledCode = new AtomicInteger(-1);

        ChthollyApplication.runContentPackCli(
                new String[]{"--mode=content_pack"},
                code -> {
                    lifecycle.add("handle");
                    handledCode.set(code);
                },
                args -> {
                    lifecycle.add("run");
                    return context;
                },
                (runningContext, generator) -> {
                    lifecycle.add("exit");
                    runningContext.close();
                    return generator.getExitCode();
                });

        assertThat(lifecycle).containsExactly("run", "exit", "handle");
        assertThat(handledCode).hasValue(2);
        verify(context, times(1)).close();
    }

    @Test
    void normalizeContentPackCliArgs_givenStandardDryRun_injectsAllReadOnlyBoundaries() {
        String[] normalized = ChthollyApplication.normalizeContentPackCliArgs(new String[]{
                "--seed.mode=content_pack",
                "--seed.dry-run=true"
        });

        assertThat(normalized).contains(
                "--seed.mode=content_pack",
                "--seed.dry-run=true",
                "--seed.cli-read-only=true",
                "--spring.main.web-application-type=none",
                "--spring.main.lazy-initialization=true",
                "--kafka.enabled=false",
                "--canal.enabled=false",
                "--bangumi.enabled=false");
    }

    @Test
    void runContentPackCli_givenFormalReadOnlyConflict_rejectsBeforeSpringLaunch() {
        ChthollyApplication.ApplicationLauncher launcher = mock(ChthollyApplication.ApplicationLauncher.class);
        AtomicInteger handledCode = new AtomicInteger(-1);

        ChthollyApplication.runContentPackCli(
                new String[]{"--mode=content-pack", "--seed.cli-read-only=true"},
                handledCode::set,
                launcher,
                (context, generator) -> generator.getExitCode());

        assertThat(handledCode).hasValue(1);
        verify(launcher, never()).run(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void runContentPackCli_givenDryRunSafetyConflict_rejectsBeforeSpringLaunch() {
        ChthollyApplication.ApplicationLauncher launcher = mock(ChthollyApplication.ApplicationLauncher.class);
        AtomicInteger handledCode = new AtomicInteger(-1);

        ChthollyApplication.runContentPackCli(
                new String[]{"--mode=content_pack", "--dry-run", "--kafka.enabled=true"},
                handledCode::set,
                launcher,
                (context, generator) -> generator.getExitCode());

        assertThat(handledCode).hasValue(1);
        verify(launcher, never()).run(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void runContentPackCli_givenStandardDryRun_launchesWithNormalizedSafetyArguments() {
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        SeedRunner seedRunner = mock(SeedRunner.class);
        when(context.getBean(SeedRunner.class)).thenReturn(seedRunner);
        AtomicReference<String[]> launchedArgs = new AtomicReference<>();

        ChthollyApplication.runContentPackCli(
                new String[]{"--seed.mode=content_pack", "--seed.dry-run=true"},
                ignored -> { },
                args -> {
                    launchedArgs.set(args);
                    return context;
                },
                (runningContext, generator) -> generator.getExitCode());

        assertThat(launchedArgs.get()).contains(
                "--seed.cli-read-only=true",
                "--spring.main.web-application-type=none",
                "--spring.main.lazy-initialization=true",
                "--kafka.enabled=false",
                "--canal.enabled=false",
                "--bangumi.enabled=false");
    }

    @Test
    void normalizeContentPackCliArgs_givenLegacyMode_returnsArgumentsUnchanged() {
        String[] legacy = {"--mode=accounts", "--dry-run"};

        assertThat(ChthollyApplication.normalizeContentPackCliArgs(legacy)).containsExactly(legacy);
    }
}
