package com.chtholly.integration;

import com.chtholly.bangumi.service.BangumiService;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that upstream LLM transport failures remain bounded and never mutate post state.
 */
@AutoConfigureMockMvc
@Import(LlmDegradationIT.LlmStubConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LlmDegradationIT extends AbstractGoldenPathIT {

    private static final long USER_ID = 401L;
    private static final MockWebServer LLM = startLlmStub();

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void registerLlmProperties(DynamicPropertyRegistry registry) {
        registry.add("llm.enabled", () -> "true");
        registry.add("rag.enabled", () -> "false");
        registry.add("test.llm.base-url", () -> LLM.url("/").toString());
    }

    @BeforeEach
    void resetState() {
        cleanRedis();
        cleanDatabase();
        jdbc.update("INSERT INTO users (id, nickname, handle) VALUES (?, ?, ?)",
                USER_ID, "LLM Failure User", "llm-failure-user");
    }

    @AfterAll
    static void stopLlmStub() throws IOException {
        LLM.shutdown();
    }

    @Test
    void http500ReturnsInternalErrorWithoutWrites() throws Exception {
        LLM.enqueue(new MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"message\":\"stub failure\"}}"));

        assertStableFailureWithoutWrites();
    }

    @Test
    void noResponseTimesOutAsInternalErrorWithoutWrites() throws Exception {
        LLM.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        assertStableFailureWithoutWrites();
    }

    @Test
    void disconnectedConnectionReturnsInternalErrorWithoutWrites() throws Exception {
        LLM.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));

        assertStableFailureWithoutWrites();
    }

    private void assertStableFailureWithoutWrites() throws Exception {
        mockMvc.perform(post("/api/v1/posts/description/suggest")
                        .with(jwt().jwt(token -> token.claim("uid", USER_ID)))
                        .contentType("application/json")
                        .content("{\"content\":\"A local stub must fail without creating a post.\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM posts", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Long.class)).isZero();
    }

    private static MockWebServer startLlmStub() {
        MockWebServer server = new MockWebServer();
        try {
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start local LLM stub", e);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LlmStubConfiguration {

        @Bean(name = "deepSeekChatModel")
        ChatModel deepSeekChatModel(@Value("${test.llm.base-url}") String baseUrl) {
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();
            JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
            requestFactory.setReadTimeout(Duration.ofSeconds(2));
            RestClient.Builder restClient = RestClient.builder().requestFactory(requestFactory);
            DeepSeekApi api = DeepSeekApi.builder()
                    .baseUrl(baseUrl)
                    .apiKey("integration-test-key")
                    .restClientBuilder(restClient)
                    .build();
            return DeepSeekChatModel.builder()
                    .deepSeekApi(api)
                    .defaultOptions(DeepSeekChatOptions.builder().model("deepseek-chat").build())
                    .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                    .build();
        }

        @Bean
        VectorStore vectorStore() {
            return mock(VectorStore.class);
        }

        @Bean
        BangumiService bangumiService() {
            return mock(BangumiService.class);
        }
    }
}
