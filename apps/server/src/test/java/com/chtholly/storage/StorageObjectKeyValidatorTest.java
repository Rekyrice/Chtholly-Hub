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

    @Test
    void immutableObjectKey_acceptsOnlyCanonicalPostContentUploadAddresses() {
        assertThat(StorageObjectKeyValidator.isImmutableObjectKey(
                "posts/42/content-uploads/" + "a".repeat(32) + ".md"))
                .isTrue();

        assertThat(StorageObjectKeyValidator.isImmutableObjectKey(
                "posts/42/content-uploads/" + "a".repeat(31) + ".md"))
                .isFalse();
        assertThat(StorageObjectKeyValidator.isImmutableObjectKey(
                "posts/42/content-uploads/" + "A".repeat(32) + ".md"))
                .isFalse();
        assertThat(StorageObjectKeyValidator.isImmutableObjectKey("posts/42/content.md"))
                .isFalse();
    }

    @Test
    void postContentObjectKeyBelongsToPost_acceptsNewAndHistoricalKeysButRejectsAnotherPost() {
        assertThatCode(() -> StorageObjectKeyValidator.assertPostContentObjectKeyBelongsToPost(
                "posts/42/content-uploads/" + "a".repeat(32) + ".json", 42L))
                .doesNotThrowAnyException();
        assertThatCode(() -> StorageObjectKeyValidator.assertPostContentObjectKeyBelongsToPost(
                "posts/42/content.txt", 42L))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> StorageObjectKeyValidator.assertPostContentObjectKeyBelongsToPost(
                "posts/43/content-uploads/" + "a".repeat(32) + ".md", 42L))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getMessage()).isEqualTo("正文对象不属于该文章"));
    }
}
