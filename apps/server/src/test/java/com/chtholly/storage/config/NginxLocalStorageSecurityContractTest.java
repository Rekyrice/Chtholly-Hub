package com.chtholly.storage.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NginxLocalStorageSecurityContractTest {

    private static final String UNSAFE_LEGACY_CONTENT_LOCATION =
            "location ~* ^/uploads/posts/[0-9]+/content(?![.](?:md|txt|json)$)(?:[.][^/]*)?$ {";

    @Test
    void nginxRejectsUnsafeLegacyPostBodiesBeforeServingTheUploadsAlias() throws IOException {
        assertRejectsUnsafeLegacyPostBodiesBeforeServingTheUploadsAlias(findNginxConfig("default.conf"));
        assertRejectsUnsafeLegacyPostBodiesBeforeServingTheUploadsAlias(findNginxConfig("https.conf.template"));
    }

    private static void assertRejectsUnsafeLegacyPostBodiesBeforeServingTheUploadsAlias(Path configPath)
            throws IOException {
        String config = Files.readString(configPath).replace("\r\n", "\n");

        int activeBlock = config.indexOf("    " + UNSAFE_LEGACY_CONTENT_LOCATION);
        int activeUploadsAlias = config.indexOf("    location /uploads/ {");
        assertThat(activeBlock).isGreaterThanOrEqualTo(0).isLessThan(activeUploadsAlias);
        assertThat(config.substring(activeBlock, activeUploadsAlias))
                .contains("return 404;");
        assertThat(countOccurrences(config, UNSAFE_LEGACY_CONTENT_LOCATION)).isEqualTo(1);
    }

    private static Path findNginxConfig(String fileName) {
        return List.of(
                        Path.of("..", "..", "docker", "nginx", fileName),
                        Path.of("docker", "nginx", fileName))
                .stream()
                .map(path -> path.toAbsolutePath().normalize())
                .filter(Files::isRegularFile)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("docker/nginx/" + fileName + " is missing"));
    }

    private static int countOccurrences(String source, String expected) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(expected, offset)) >= 0) {
            count++;
            offset += expected.length();
        }
        return count;
    }
}
