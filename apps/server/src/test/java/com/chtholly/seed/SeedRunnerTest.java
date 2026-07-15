package com.chtholly.seed;

import com.chtholly.seed.contentpack.ContentPackImportService;
import com.chtholly.seed.contentpack.model.ContentPackImportReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeedRunnerTest {

    @Mock
    private SeedOrchestrator orchestrator;
    @Mock
    private ContentPackImportService contentPackImportService;

    private SeedProperties properties;
    private SeedRunner runner;

    @BeforeEach
    void setUp() {
        properties = new SeedProperties();
        properties.setContentPackPath("../../content/seed/content-v2");
        runner = new SeedRunner(properties, orchestrator, contentPackImportService, new ObjectMapper());
    }

    @Test
    void given_contentPackDryRun_when_run_then_delegatesToImporterWithNormalizedPath() {
        when(contentPackImportService.run(any(), eq(true))).thenReturn(report("validated"));

        runner.run("--seed.mode=content_pack", "--seed.dry-run=true");

        ArgumentCaptor<Path> root = ArgumentCaptor.forClass(Path.class);
        verify(contentPackImportService).run(root.capture(), eq(true));
        assertThat(root.getValue()).isAbsolute().isEqualTo(root.getValue().normalize());
        assertThat(runner.contentPackExitCode()).isZero();
        verify(orchestrator, never()).run(any());
    }

    @Test
    void given_legacyMode_when_run_then_preservesOrchestratorRoutingWithoutCliExitState() {
        when(orchestrator.run(new SeedRunOptions(SeedRunMode.ACCOUNTS, true)))
                .thenReturn(SeedRunSummary.skipped(SeedRunMode.ACCOUNTS, true));

        runner.run("--mode=accounts", "--dry-run");

        verify(orchestrator).run(new SeedRunOptions(SeedRunMode.ACCOUNTS, true));
        verify(contentPackImportService, never()).run(any(), eq(true));
        assertThat(runner.contentPackExitCode()).isZero();
    }

    @Test
    void given_failedFormalReviewPack_when_run_thenStoresExitCodeOneWithoutThrowing() {
        when(contentPackImportService.run(any(), eq(false))).thenReturn(report("failed"));

        runner.run("--mode=content-pack");

        assertThat(runner.contentPackExitCode()).isEqualTo(1);
    }

    @Test
    void given_partialFormalImport_when_run_thenStoresExitCodeTwo() {
        when(contentPackImportService.run(any(), eq(false))).thenReturn(report("partial"));

        runner.run("--mode=content_pack");

        assertThat(runner.contentPackExitCode()).isEqualTo(2);
    }

    @Test
    void given_completedFormalImport_when_run_thenStoresExitCodeZero() {
        when(contentPackImportService.run(any(), eq(false))).thenReturn(report("completed"));

        runner.run("--mode=content_pack");

        assertThat(runner.contentPackExitCode()).isZero();
    }

    private static ContentPackImportReport report(String status) {
        return new ContentPackImportReport(status, "failed".equals(status) ? "manifest-stage" : null,
                "chtholly-community", "content-v2", null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
