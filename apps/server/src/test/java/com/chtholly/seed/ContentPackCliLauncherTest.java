package com.chtholly.seed;

import com.chtholly.ChthollyApplication;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContentPackCliLauncherTest {

    @Test
    void contentPackCliDetection_doesNotCaptureLegacyModesOrNormalServer() {
        assertThat(ContentPackCliLauncher.isContentPackCli(new String[]{"--seed.mode=content_pack"})).isTrue();
        assertThat(ContentPackCliLauncher.isContentPackCli(new String[]{"--mode=content-pack"})).isTrue();
        assertThat(ContentPackCliLauncher.isContentPackCli(new String[]{"--mode=full"})).isFalse();
        assertThat(ContentPackCliLauncher.isContentPackCli(new String[]{"--mode=unknown"})).isFalse();
        assertThat(ContentPackCliLauncher.isContentPackCli(new String[]{})).isFalse();
    }

    @Test
    void launch_waitsForRunnerThenClosesOnceAndPropagatesExitCode() {
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        SeedRunner seedRunner = mock(SeedRunner.class);
        when(context.getBean(SeedRunner.class)).thenReturn(seedRunner);
        when(seedRunner.contentPackExitCode()).thenReturn(2);
        List<String> lifecycle = new ArrayList<>();
        AtomicInteger handledCode = new AtomicInteger(-1);

        ContentPackCliLauncher.launch(
                ChthollyApplication.class,
                new String[]{"--mode=content_pack"},
                code -> {
                    lifecycle.add("handle");
                    handledCode.set(code);
                },
                (applicationClass, args) -> {
                    lifecycle.add("run");
                    assertThat(applicationClass).isEqualTo(ChthollyApplication.class);
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
    void normalizeArgs_givenStandardDryRun_injectsAllReadOnlyBoundaries() {
        String[] normalized = ContentPackCliLauncher.normalizeArgs(new String[]{
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
    void launch_givenFormalReadOnlyConflict_rejectsBeforeSpringLaunch() {
        ContentPackCliLauncher.ApplicationLauncher launcher = mock(ContentPackCliLauncher.ApplicationLauncher.class);
        AtomicInteger handledCode = new AtomicInteger(-1);

        ContentPackCliLauncher.launch(
                ChthollyApplication.class,
                new String[]{"--mode=content-pack", "--seed.cli-read-only=true"},
                handledCode::set,
                launcher,
                (context, generator) -> generator.getExitCode());

        assertThat(handledCode).hasValue(1);
        verify(launcher, never()).run(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void launch_givenDryRunSafetyConflict_rejectsBeforeSpringLaunch() {
        ContentPackCliLauncher.ApplicationLauncher launcher = mock(ContentPackCliLauncher.ApplicationLauncher.class);
        AtomicInteger handledCode = new AtomicInteger(-1);

        ContentPackCliLauncher.launch(
                ChthollyApplication.class,
                new String[]{"--mode=content_pack", "--dry-run", "--kafka.enabled=true"},
                handledCode::set,
                launcher,
                (context, generator) -> generator.getExitCode());

        assertThat(handledCode).hasValue(1);
        verify(launcher, never()).run(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void launch_givenStandardDryRun_launchesWithNormalizedSafetyArguments() {
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        SeedRunner seedRunner = mock(SeedRunner.class);
        when(context.getBean(SeedRunner.class)).thenReturn(seedRunner);
        AtomicReference<String[]> launchedArgs = new AtomicReference<>();

        ContentPackCliLauncher.launch(
                ChthollyApplication.class,
                new String[]{"--seed.mode=content_pack", "--seed.dry-run=true"},
                ignored -> { },
                (applicationClass, args) -> {
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
    void normalizeArgs_givenLegacyMode_returnsArgumentsUnchanged() {
        String[] legacy = {"--mode=accounts", "--dry-run"};

        assertThat(ContentPackCliLauncher.normalizeArgs(legacy)).containsExactly(legacy);
    }
}
