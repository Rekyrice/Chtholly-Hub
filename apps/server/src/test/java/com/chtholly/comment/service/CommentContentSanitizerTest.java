package com.chtholly.comment.service;

import com.chtholly.comment.config.CommentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommentContentSanitizerTest {

    private CommentContentSanitizer sanitizer;

    @BeforeEach
    void setUp() {
        CommentProperties properties = new CommentProperties();
        properties.setSensitiveWords(java.util.List.of("敏感词"));
        sanitizer = new CommentContentSanitizer(properties);
    }

    @Test
    void stripsHtmlTags() {
        assertThat(sanitizer.sanitize("<script>alert(1)</script>hello"))
                .isEqualTo("hello");
    }

    @Test
    void replacesSensitiveWords() {
        assertThat(sanitizer.sanitize("这里有敏感词内容"))
                .isEqualTo("这里有***内容");
    }

    @Test
    void trimsContent() {
        assertThat(sanitizer.sanitize("  你好  "))
                .isEqualTo("你好");
    }
}
