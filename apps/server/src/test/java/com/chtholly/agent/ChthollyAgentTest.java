package com.chtholly.agent;

import com.chtholly.agent.config.AgentProperties;
import com.chtholly.agent.observability.AgentMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChthollyAgentTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ChatClient chatClient;
    @Mock
    private AgentMetrics agentMetrics;

    private AgentProperties properties;
    private ObjectMapper objectMapper;
    private AgentJsonExtractor jsonExtractor;
    private CharacterSoulService characterSoulService;
    private ChthollyAgent agent;
    private List<AgentEvent> events;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        jsonExtractor = new AgentJsonExtractor(objectMapper);
        properties = new AgentProperties();
        properties.setMaxSteps(5);
        properties.setLlmTimeoutSeconds(5);
        properties.setStreamCharDelayMs(0);
        properties.setToolTimeoutSeconds(5);
        characterSoulService = new CharacterSoulService("""
                # 珂朵莉

                认真到笨拙，但不会编造答案。
                """);
        agent = new ChthollyAgent(chatClient, properties, objectMapper, List.of(mockTool()), jsonExtractor,
                agentMetrics, characterSoulService);
        events = new ArrayList<>();
    }

    @Test
    void given_finalAction_when_run_then_completesInOneStep() {
        stubLlmCall("{\"action\":\"final\",\"answer\":\"占位\"}");
        stubStream("一步完成的回答");

        agent.run("问题", 1L, null, events::add);

        assertThat(eventTypes()).contains("think", "delta", "final");
        assertThat(eventTypes()).doesNotContain("act", "observe", "error");
        assertThat(lastContent("final")).isEqualTo("一步完成的回答");
    }

    @Test
    void given_toolCall_when_run_then_toolExecutedAndObservationFed() {
        AtomicInteger llmCalls = new AtomicInteger();
        when(chatClient.prompt().system(anyString()).user(anyString()).options(any()).call().content())
                .thenAnswer(inv -> {
                    if (llmCalls.getAndIncrement() == 0) {
                        return "{\"action\":\"test_tool\",\"input\":{\"keyword\":\"re0\"}}";
                    }
                    return "{\"action\":\"final\",\"answer\":\"占位\"}";
                });
        stubStream("根据工具结果回答");

        agent.run("查一下 re0", 2L, null, events::add);

        assertThat(eventTypes()).containsSubsequence("think", "act", "observe", "think", "final");
        assertThat(findFirst("act").path("tool").asText()).isEqualTo("test_tool");
        assertThat(findFirst("observe").path("content").asText()).isEqualTo("mock observation");
    }

    @Test
    void given_agentRuns_when_buildingPrompt_then_usesSoulAndLayeredPrompt() {
        stubLlmCall("{\"action\":\"final\",\"answer\":\"占位\"}");
        stubStream("嗯，还行吧");

        agent.run("你很厉害", 1L, null, events::add);

        org.mockito.Mockito.verify(chatClient.prompt()).system(org.mockito.ArgumentMatchers.<String>argThat(prompt ->
                prompt.contains("## 你的身份")
                        && prompt.contains("# 珂朵莉")
                        && prompt.contains("认真到笨拙，但不会编造答案。")
                        && prompt.contains("## 可用工具")
                        && prompt.contains("### test_tool")
                        && prompt.contains("## 工具使用准则")
                        && prompt.contains("1. 优先用工具获取事实，不确定时查一下再回答")
                        && prompt.contains("## 对话历史")
                        && prompt.contains("## 用户的问题")
                        && prompt.contains("你很厉害")
                        && !prompt.contains("[系统提示]")
                        && !prompt.contains("工具选择与意图判断")
        ));
    }

    @Test
    void given_toolThrows_when_run_then_errorBecomesObservation() {
        AgentTool failingTool = new AgentTool() {
            @Override
            public String name() {
                return "fail_tool";
            }

            @Override
            public String description() {
                return "fail";
            }

            @Override
            public String execute(Map<String, Object> input, long userId) {
                throw new RuntimeException("boom");
            }
        };
        agent = new ChthollyAgent(chatClient, properties, objectMapper, List.of(failingTool), jsonExtractor,
                agentMetrics, characterSoulService);

        AtomicInteger llmCalls = new AtomicInteger();
        when(chatClient.prompt().system(anyString()).user(anyString()).options(any()).call().content())
                .thenAnswer(inv -> {
                    if (llmCalls.getAndIncrement() == 0) {
                        return "{\"action\":\"fail_tool\",\"input\":{}}";
                    }
                    return "{\"action\":\"final\",\"answer\":\"占位\"}";
                });
        stubStream("继续回答");

        agent.run("测试", 1L, null, events::add);

        assertThat(findFirst("observe").path("content").asText()).contains("工具执行失败");
        assertThat(eventTypes()).contains("final");
    }

    @Test
    void given_llmSlow_when_run_then_timeoutHandled() {
        properties.setLlmTimeoutSeconds(1);
        ChatClient.CallResponseSpec callSpec = org.mockito.Mockito.mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt().system(anyString()).user(anyString()).options(any()).call())
                .thenReturn(callSpec);
        when(callSpec.content()).thenAnswer(inv -> {
            Thread.sleep(1500);
            return "{\"action\":\"final\",\"answer\":\"late\"}";
        });

        agent.run("超时测试", 1L, null, events::add);

        assertThat(eventTypes()).contains("error");
        assertThat(findFirst("error").path("message").asText()).contains("超时");
    }

    @Test
    void given_alwaysToolCall_when_maxStepsReached_then_terminatesWithoutInfiniteLoop() {
        properties.setMaxSteps(2);
        when(chatClient.prompt().system(anyString()).user(anyString()).options(any()).call().content())
                .thenReturn("{\"action\":\"test_tool\",\"input\":{\"keyword\":\"x\"}}");

        agent.run("循环测试", 1L, null, events::add);

        assertThat(eventTypes()).contains("error");
        assertThat(findFirst("error").path("message").asText()).contains("最大推理步数");
        assertThat(eventTypes().stream().filter(t -> "act".equals(t)).count()).isEqualTo(2);
    }

    private AgentTool mockTool() {
        return new AgentTool() {
            @Override
            public String name() {
                return "test_tool";
            }

            @Override
            public String description() {
                return "测试工具";
            }

            @Override
            public String execute(Map<String, Object> input, long userId) {
                return "mock observation";
            }
        };
    }

    private void stubLlmCall(String json) {
        when(chatClient.prompt().system(anyString()).user(anyString()).options(any()).call().content())
                .thenReturn(json);
    }

    private void stubStream(String text) {
        when(chatClient.prompt().system(anyString()).user(anyString()).options(any()).stream().content())
                .thenReturn(Flux.fromArray(text.split("")));
    }

    private List<String> eventTypes() {
        return events.stream().map(AgentEvent::type).toList();
    }

    private com.fasterxml.jackson.databind.JsonNode findFirst(String type) {
        return events.stream()
                .filter(e -> type.equals(e.type()))
                .findFirst()
                .orElseThrow()
                .data();
    }

    private String lastContent(String type) {
        return events.stream()
                .filter(e -> type.equals(e.type()))
                .reduce((a, b) -> b)
                .orElseThrow()
                .data()
                .path("content")
                .asText();
    }
}
