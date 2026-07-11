package com.chtholly.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PostAgentDependencyTest {

    @Test
    void postPackageDoesNotDependOnAgentPackage() {
        Path postPackage = Path.of("src/main/java/com/chtholly/post");

        List<Path> violations;
        try (var files = Files.walk(postPackage)) {
            violations = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> containsAgentImport(path, postPackage))
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to inspect post package at " + postPackage, exception);
        }

        assertThat(violations).isEmpty();
    }

    private boolean containsAgentImport(Path sourceFile, Path postPackage) {
        try {
            return Files.readString(sourceFile).contains("import com.chtholly.agent.");
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to read Java source " + sourceFile + " under " + postPackage,
                    exception);
        }
    }
}
