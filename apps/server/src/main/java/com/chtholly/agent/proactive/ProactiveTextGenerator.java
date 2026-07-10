package com.chtholly.agent.proactive;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

/** Generates short proactive copy while allowing a no-LLM fallback. */
@FunctionalInterface
interface ProactiveTextGenerator {
    String generate(String prompt);

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
