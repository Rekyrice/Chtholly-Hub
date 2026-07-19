package com.chtholly.auth.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigStorageMatcherTest {

    @Test
    void publicStorageMatcherIsEnabledOnlyForSafeLocalPaths() {
        assertThat(SecurityConfig.localStoragePattern("local", "/uploads/"))
                .contains("/uploads/**");
        assertThat(SecurityConfig.localStoragePattern("oss", "/uploads"))
                .isEmpty();
        assertThat(SecurityConfig.localStoragePattern("local", "/"))
                .isEmpty();
        assertThat(SecurityConfig.localStoragePattern("local", "/api"))
                .isEmpty();
        assertThat(SecurityConfig.localStoragePattern("local", "/actuator/files"))
                .isEmpty();
        assertThat(SecurityConfig.localStoragePattern("local", "/../api"))
                .isEmpty();
        assertThat(SecurityConfig.localStoragePattern("local", "/uploads/.."))
                .isEmpty();
        assertThat(SecurityConfig.localStoragePattern("local", "https://cdn.example.test/files"))
                .isEmpty();
    }
}
