package com.chtholly.storage;

import com.chtholly.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageUploadValidatorTest {

    @Test
    void validate_acceptsCanonicalPostContentUploadKeyWithMatchingType() {
        assertThatCode(() -> StorageUploadValidator.validate(
                "posts/42/content-uploads/" + "a".repeat(32) + ".md",
                content("text/markdown", "hello")))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_rejectsHistoricalAndMalformedPostContentUploadKeys() {
        UploadContent markdown = content("text/markdown", "hello");

        assertThatThrownBy(() -> StorageUploadValidator.validate("posts/42/content.md", markdown))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> StorageUploadValidator.validate(
                "posts/42/content-uploads/" + "a".repeat(31) + ".md", markdown))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> StorageUploadValidator.validate(
                "posts/42/content-uploads/" + "A".repeat(32) + ".md", markdown))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void validate_rejectsPostContentExtensionThatDoesNotMatchType() {
        assertThatThrownBy(() -> StorageUploadValidator.validate(
                "posts/42/content-uploads/" + "a".repeat(32) + ".json",
                content("text/markdown", "hello")))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> org.assertj.core.api.Assertions.assertThat(error.getMessage())
                                .isEqualTo("文件扩展名与类型不匹配"));
    }

    private static UploadContent content(String contentType, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return new UploadContent() {
            @Override
            public boolean isEmpty() {
                return bytes.length == 0;
            }

            @Override
            public long size() {
                return bytes.length;
            }

            @Override
            public String contentType() {
                return contentType;
            }

            @Override
            public InputStream openStream() {
                return new ByteArrayInputStream(bytes);
            }

            @Override
            public byte[] readAllBytes() {
                return bytes.clone();
            }
        };
    }
}
