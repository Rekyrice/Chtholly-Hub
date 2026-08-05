package com.chtholly.auth.token;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenStoreTest {

    @Test
    void epochCaptureAndConditionalStoreAreRequiredAuthorityOperations()
            throws Exception {
        var capture = RefreshTokenStore.class.getMethod(
                "captureEpoch", long.class);
        var conditionalStore = RefreshTokenStore.class.getMethod(
                "storeTokenIfEpochMatches",
                long.class,
                String.class,
                Duration.class,
                long.class);

        assertThat(capture.isDefault()).isFalse();
        assertThat(Modifier.isAbstract(capture.getModifiers())).isTrue();
        assertThat(conditionalStore.isDefault()).isFalse();
        assertThat(Modifier.isAbstract(conditionalStore.getModifiers()))
                .isTrue();
    }

    @Test
    void rotationMustBeImplementedAtomicallyByEveryStorageAdapter()
            throws Exception {
        var method = RefreshTokenStore.class.getMethod(
                "rotateToken",
                long.class,
                String.class,
                String.class,
                Duration.class);

        assertThat(method.isDefault()).isFalse();
        assertThat(Modifier.isAbstract(method.getModifiers())).isTrue();
    }
}
