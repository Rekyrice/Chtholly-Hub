package com.chtholly.agent.runtime;

import com.chtholly.agent.config.AgentProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentLlmInvokerTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ChatClient chatClient;

    private AgentProperties properties;
    private ExecutorService executor;
    private AgentLlmInvoker invoker;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.setModel("deepseek-test");
        properties.setLlmTimeoutSeconds(2);
        executor = Executors.newSingleThreadExecutor();
        invoker = new AgentLlmInvoker(chatClient, properties, executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void callReturnsContentAndAppliesDeepSeekOptions() throws Exception {
        when(chatClient.prompt().system(anyString()).user(anyString()).options(any()).call().content())
                .thenReturn("model response");

        String result = invoker.call("system", "user", 0.1, 1024);

        assertThat(result).isEqualTo("model response");
        ArgumentCaptor<DeepSeekChatOptions> optionsCaptor = ArgumentCaptor.forClass(DeepSeekChatOptions.class);
        verify(chatClient.prompt().system("system").user("user"), atLeastOnce()).options(optionsCaptor.capture());
        DeepSeekChatOptions options = optionsCaptor.getAllValues().getLast();
        assertThat(options.getModel()).isEqualTo("deepseek-test");
        assertThat(options.getTemperature()).isEqualTo(0.1);
        assertThat(options.getMaxTokens()).isEqualTo(1024);
    }

    @Test
    void callCancelsAndInterruptsExecutionThreadOnTimeout() throws Exception {
        properties.setLlmTimeoutSeconds(1);
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        when(chatClient.prompt().system(anyString()).user(anyString()).options(any()).call().content())
                .thenAnswer(invocation -> {
                    started.countDown();
                    try {
                        Thread.sleep(10_000);
                        return "late";
                    } catch (InterruptedException e) {
                        interrupted.set(true);
                        throw e;
                    }
                });

        assertThatThrownBy(() -> invoker.call("system", "user", 0.1, 1024))
                .isInstanceOf(TimeoutException.class);

        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(waitUntilTrue(interrupted, Duration.ofSeconds(1))).isTrue();
    }

    @Test
    void callUnwrapsExecutionExceptionCause() {
        IllegalStateException failure = new IllegalStateException("model failed");
        when(chatClient.prompt().system(anyString()).user(anyString()).options(any()).call().content())
                .thenThrow(failure);

        assertThatThrownBy(() -> invoker.call("system", "user", 0.1, 1024))
                .isSameAs(failure);
    }

    @Test
    void streamReturnsChunks() {
        when(chatClient.prompt().system(anyString()).user(anyString()).options(any()).stream().content())
                .thenReturn(Flux.just("first", "second"));

        StepVerifier.create(invoker.stream("system", "user", 0.3, 1024))
                .expectNext("first", "second")
                .verifyComplete();
    }

    @Test
    void streamTimesOutWhenNoChunkArrives() {
        properties.setLlmTimeoutSeconds(1);
        when(chatClient.prompt().system(anyString()).user(anyString()).options(any()).stream().content())
                .thenReturn(Flux.never());

        StepVerifier.create(invoker.stream("system", "user", 0.3, 1024))
                .expectError(TimeoutException.class)
                .verify(Duration.ofSeconds(3));
    }

    private boolean waitUntilTrue(AtomicBoolean value, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!value.get() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        return value.get();
    }
}
