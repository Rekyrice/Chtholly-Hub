package com.chtholly.agent.runtime;

import com.chtholly.agent.config.AgentProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Invokes the configured chat model for blocking ReAct calls and streaming final answers.
 *
 * <p>Blocking requests run on a reusable virtual-thread executor so timed-out work can be
 * cancelled and interrupted. Streaming requests apply the same configured timeout at the
 * publisher boundary.
 */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentLlmInvoker {

    private final ChatClient chatClient;
    private final AgentProperties properties;
    private final ExecutorService executor;
    private final boolean ownsExecutor;

    /**
     * Creates an invoker backed by a dedicated virtual-thread executor.
     *
     * @param chatClient configured Spring AI chat client
     * @param properties agent runtime properties
     */
    @Autowired
    public AgentLlmInvoker(ChatClient chatClient, AgentProperties properties) {
        this(chatClient, properties, Executors.newVirtualThreadPerTaskExecutor(), true);
    }

    AgentLlmInvoker(ChatClient chatClient, AgentProperties properties, ExecutorService executor) {
        this(chatClient, properties, executor, false);
    }

    private AgentLlmInvoker(
            ChatClient chatClient,
            AgentProperties properties,
            ExecutorService executor,
            boolean ownsExecutor) {
        this.chatClient = chatClient;
        this.properties = properties;
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
    }

    /**
     * Performs a blocking model call with the configured timeout.
     *
     * @param system system prompt
     * @param userPrompt user prompt
     * @param temperature sampling temperature
     * @param maxTokens maximum generated tokens
     * @return model response content
     * @throws TimeoutException if the configured deadline is exceeded
     * @throws InterruptedException if the waiting thread is interrupted
     * @throws Exception if the model invocation fails
     */
    public String call(
            String system,
            String userPrompt,
            double temperature,
            int maxTokens) throws Exception {
        int timeoutSeconds = Math.max(1, properties.getLlmTimeoutSeconds());
        Future<String> future = executor.submit(() -> chatClient.prompt()
                .system(system)
                .user(userPrompt)
                .options(chatOptions(temperature, maxTokens))
                .call()
                .content());
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new RuntimeException(cause);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    /**
     * Streams model response chunks with the configured timeout.
     *
     * @param system system prompt
     * @param userPrompt user prompt
     * @param temperature sampling temperature
     * @param maxTokens maximum generated tokens
     * @return response chunk publisher
     */
    public Flux<String> stream(
            String system,
            String userPrompt,
            double temperature,
            int maxTokens) {
        int timeoutSeconds = Math.max(1, properties.getLlmTimeoutSeconds());
        return chatClient.prompt()
                .system(system)
                .user(userPrompt)
                .options(chatOptions(temperature, maxTokens))
                .stream()
                .content()
                .timeout(Duration.ofSeconds(timeoutSeconds));
    }

    @PreDestroy
    void closeOwnedExecutor() {
        if (ownsExecutor) {
            executor.shutdownNow();
        }
    }

    private DeepSeekChatOptions chatOptions(double temperature, int maxTokens) {
        return DeepSeekChatOptions.builder()
                .model(properties.getModel())
                .temperature(temperature)
                .maxTokens(maxTokens)
                .build();
    }
}
