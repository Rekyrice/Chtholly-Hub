package com.chtholly.seed;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SeedCliScriptTest {

    @Test
    void contentPackFormalRun_usesSynchronousCountersAndDisablesUnrelatedBackgroundWork() throws IOException {
        Path script = Path.of(System.getProperty("user.dir"), "../../scripts/dev/run-seed.ps1").normalize();
        String source = Files.readString(script);

        assertThat(source)
                .contains("if ($isContentPack) {")
                .contains("--kafka.enabled=false --canal.enabled=false")
                .contains("--bangumi.enabled=false --llm.enabled=false")
                .contains("$env:LLM_ENABLED = \"false\"")
                .contains("Where-Object { $_ -and $_ -ne \"llm\" }")
                .contains("if ($isContentPack -and -not $DryRun) {")
                .contains("Get-NetTCPConnection -LocalPort $serverPort -State Listen")
                .contains("Stop the main backend before a formal content-pack import");
    }

    @Test
    void contentPackDryRun_passesReadOnlyAndDisablesBackgroundInfrastructure() throws IOException {
        Path script = Path.of(System.getProperty("user.dir"), "../../scripts/dev/run-seed.ps1").normalize();
        String source = Files.readString(script);

        assertThat(source)
                .contains("--seed.cli-read-only=true")
                .contains("--kafka.enabled=false")
                .contains("--canal.enabled=false")
                .contains("--spring.main.web-application-type=none")
                .contains("--seed.dry-run=true")
                .contains("if (-not ($isContentPack -and $DryRun))")
                .contains("ResetMarker cannot be combined with DryRun")
                .contains("if (-not $isContentPack)")
                .doesNotContain("New-Item")
                .doesNotContain("Write-Host $env:");
    }

    @Test
    void runner_restoresCallerWorkingDirectoryAfterCompile() throws IOException {
        Path script = Path.of(System.getProperty("user.dir"), "../../scripts/dev/run-seed.ps1").normalize();
        String source = Files.readString(script);

        assertThat(source)
                .contains("Push-Location (Join-Path $RepoRoot \"apps/server\")")
                .contains("finally {\n    Pop-Location\n}")
                .doesNotContain("Set-Location (Join-Path $RepoRoot \"apps/server\")");
    }

    @Test
    void runner_restoresCallerEnvironmentEvenWhenItExitsWithAReport() throws IOException {
        Path script = Path.of(System.getProperty("user.dir"), "../../scripts/dev/run-seed.ps1").normalize();
        String source = Files.readString(script);

        assertThat(source)
                .contains("$hadServerPort = Test-Path Env:SERVER_PORT")
                .contains("$hadLlmEnabled = Test-Path Env:LLM_ENABLED")
                .contains("$hadSpringProfiles = Test-Path Env:SPRING_PROFILES_ACTIVE")
                .contains("Remove-Item Env:SERVER_PORT -ErrorAction SilentlyContinue")
                .contains("Remove-Item Env:LLM_ENABLED -ErrorAction SilentlyContinue")
                .contains("Remove-Item Env:SPRING_PROFILES_ACTIVE -ErrorAction SilentlyContinue")
                .contains("finally {\n    Restore-SeedRunnerEnvironment\n}");
    }
}
