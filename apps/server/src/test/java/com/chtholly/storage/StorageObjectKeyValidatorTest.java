package com.chtholly.storage;

import com.chtholly.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageObjectKeyValidatorTest {

    @Test
    void acceptsNormalKey() {
        assertThatCode(() -> StorageObjectKeyValidator.assertSafeObjectKey("posts/1/content.md"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsPathTraversal() {
        assertThatThrownBy(() -> StorageObjectKeyValidator.assertSafeObjectKey("posts/../secret"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsAbsolutePath() {
        assertThatThrownBy(() -> StorageObjectKeyValidator.assertSafeObjectKey("/etc/passwd"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void immutableObjectKey_acceptsOnlyCanonicalDraftEditContentAddresses() {
        assertThat(StorageObjectKeyValidator.isImmutableObjectKey(
                "posts/42/content-edits/" + "a".repeat(64) + ".md"))
                .isTrue();

        assertThat(StorageObjectKeyValidator.isImmutableObjectKey(
                "posts/0/content-edits/" + "a".repeat(64) + ".md"))
                .isFalse();
        assertThat(StorageObjectKeyValidator.isImmutableObjectKey(
                "posts/42/content-edits/" + "A".repeat(64) + ".md"))
                .isFalse();
        assertThat(StorageObjectKeyValidator.isImmutableObjectKey(
                "posts/42/content-edits/" + "a".repeat(63) + ".md"))
                .isFalse();
        assertThat(StorageObjectKeyValidator.isImmutableObjectKey(
                "posts/42/content-edits/" + "a".repeat(64) + ".txt"))
                .isFalse();
    }
}
