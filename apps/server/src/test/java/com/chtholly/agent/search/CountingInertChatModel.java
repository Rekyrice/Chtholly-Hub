package com.chtholly.agent.search;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.concurrent.atomic.AtomicInteger;

/** Fails closed if a retrieval-only integration test accidentally invokes a chat model. */
final class CountingInertChatModel implements ChatModel {

    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public ChatResponse call(Prompt prompt) {
        calls.incrementAndGet();
        throw new IllegalStateException("Chat model invocation is forbidden in retrieval infrastructure tests");
    }

    int calls() {
        return calls.get();
    }
}
