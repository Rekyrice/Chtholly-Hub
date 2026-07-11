package com.chtholly.seed;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SeedCliScriptTest {

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
}
