package com.chtholly.storage;

import com.chtholly.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

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
}
