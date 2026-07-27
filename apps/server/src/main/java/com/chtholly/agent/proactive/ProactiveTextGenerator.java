package com.chtholly.agent.proactive;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

/** Generates short proactive copy while allowing a no-LLM fallback. */
@FunctionalInterface
interface ProactiveTextGenerator {
    String generate(String prompt);

    /**
     * Generates proactive copy with an explicit soft character-range constraint.
     *
     * @param prompt base generation prompt
     * @param minCharacters minimum number of Chinese characters
     * @param maxCharacters maximum number of Chinese characters
     * @return generated copy without post-processing
     * @throws IllegalArgumentException if the range is invalid
     */
    default String generate(String prompt, int minCharacters, int maxCharacters) {
        if (minCharacters <= 0 || maxCharacters < minCharacters) {
            throw new IllegalArgumentException("Character range must satisfy min > 0 and max >= min");
        }
        return generate("""
                %s

                输出要求：最终文案控制在 %d～%d 个中文字符，保持句子完整，不要解释要求。
                """.formatted(prompt, minCharacters, maxCharacters));
    }

    static ProactiveTextGenerator from(ObjectProvider<ChatClient> chatClientProvider) {
        return prompt -> {
            ChatClient client = chatClientProvider.getIfAvailable();
            if (client == null) {
                return "";
            }
            return client.prompt().user(prompt).call().content();
        };
    }
}
