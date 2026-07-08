package com.chtholly.seed;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SeedRunModeTest {

    @Test
    void contentOnly_markerKey_isNamespaced() {
        assertThat(SeedRunMode.CONTENT_ONLY.markerKey()).isEqualTo("seed:content_only");
    }

    @Test
    void from_parsesHyphenatedMode() {
        assertThat(SeedRunMode.from("content-only")).isEqualTo(SeedRunMode.CONTENT_ONLY);
        assertThat(SeedRunMode.from("content_only")).isEqualTo(SeedRunMode.CONTENT_ONLY);
    }
}
