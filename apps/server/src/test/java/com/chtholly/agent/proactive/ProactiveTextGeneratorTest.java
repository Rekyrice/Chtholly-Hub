package com.chtholly.agent.proactive;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProactiveTextGeneratorTest {
    @Test
    void generateAddsCharacterRangeAndSentenceRequirementsToBasePrompt() {
        AtomicReference<String> capturedPrompt = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        ProactiveTextGenerator generator = prompt -> {
            capturedPrompt.set(prompt);
            calls.incrementAndGet();
            return "原样返回的生成结果  ";
        };

        String result = generator.generate("基础提示", 30, 100);

        assertThat(result).isEqualTo("原样返回的生成结果  ");
        assertThat(calls).hasValue(1);
        assertThat(capturedPrompt.get())
                .contains("基础提示")
                .contains("30～100 个中文字符")
                .contains("保持句子完整");
    }

    @Test
    void generateRejectsNonPositiveMinimum() {
        ProactiveTextGenerator generator = prompt -> "unused";

        assertThatThrownBy(() -> generator.generate("基础提示", 0, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void generateRejectsMaximumBelowMinimum() {
        ProactiveTextGenerator generator = prompt -> "unused";

        assertThatThrownBy(() -> generator.generate("基础提示", 100, 99))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
