package com.chtholly.agent.context;

import com.chtholly.agent.AgentTool;
import com.chtholly.agent.CharacterSoulService;
import com.chtholly.agent.learning.InsightService;
import com.chtholly.agent.memory.AgentMemoryStore;
import com.chtholly.agent.state.BehaviorProb;
import com.chtholly.agent.state.CharacterState;
import com.chtholly.agent.state.CharacterStateService;
import com.chtholly.agent.state.Mood;
import com.chtholly.agent.state.Needs;
import com.chtholly.agent.state.Personality;
import com.chtholly.agent.state.Relationship;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ByteArrayResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContextEngineTest {

    private CharacterStateService stateService;
    private InsightService insightService;
    private ContextEngine contextEngine;

    @BeforeEach
    void setUp() throws IOException {
        CharacterSoulService soulService = new CharacterSoulService(new ByteArrayResource("""
                # 珂朵莉

                认真到笨拙，但不会编造答案。
                """.getBytes(StandardCharsets.UTF_8)));
        stateService = mock(CharacterStateService.class);
        insightService = mock(InsightService.class);
        when(stateService.load(anyLong())).thenReturn(defaultState());
        when(stateService.getMoodBaseline()).thenReturn(-0.1);
        when(insightService.getActiveInsights(7L, 5)).thenReturn(List.of("用户喜欢简洁回答"));
        contextEngine = new ContextEngine(soulService, stateService, mock(AgentMemoryStore.class), insightService);
    }

    @Test
    void buildsLayeredPromptWithStatePageInsightsToolsHistoryAndQuestion() {
        String prompt = contextEngine.buildSystemPrompt(
                7L,
                "ws-1",
                "页面：/post/frieren-review\n标题：《芙莉莲》观后感\n标签：芙莉莲、治愈",
                List.of(mockTool()),
                "User: 上一次的问题\nAssistant: 上一次的回答",
                "你怎么看这篇文章？");

        assertThat(prompt)
                .contains("## 你的身份", "# 珂朵莉")
                .contains("## 当前状态", "你和这位用户的亲密度：熟悉（熟悉的人）", "互动次数：8")
                .contains("当前时间段：", "心境基线：-0.1")
                .contains("## 用户当前在看", "页面：/post/frieren-review", "标题：《芙莉莲》观后感")
                .contains("## 你学到的行为规则", "- 用户喜欢简洁回答")
                .contains("## 可用工具", "### test_tool", "测试工具")
                .contains("## 工具使用准则", "每次只调用一个工具")
                .contains("## 对话历史", "User: 上一次的问题")
                .contains("## 用户的问题", "你怎么看这篇文章？");
        assertThat(prompt).doesNotContain("[系统提示]");
        assertThat(prompt.length()).isLessThan(8_000);
    }

    @ParameterizedTest
    @CsvSource({
            "0,深夜",
            "2,凌晨",
            "6,早晨",
            "10,上午",
            "15,下午",
            "19,傍晚",
            "22,深夜"
    })
    void timePeriodLabelMapsHourToChinesePeriod(int hour, String expectedLabel) {
        assertThat(ContextEngine.timePeriodLabel(hour)).isEqualTo(expectedLabel);
    }

    @ParameterizedTest
    @CsvSource({
            "0.0,陌生人",
            "0.1,刚认识",
            "0.3,熟悉的人",
            "0.6,朋友",
            "0.9,很亲近的人"
    })
    void intimacyLabelMapsScoreToRelationshipLabel(double intimacy, String expectedLabel) {
        assertThat(ContextEngine.intimacyLabel(intimacy)).isEqualTo(expectedLabel);
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

    private CharacterState defaultState() {
        return new CharacterState(
                new Personality(0.7, 0.8, 0.5),
                new Mood(-0.4, 0.5, 0.0),
                new Relationship(0.42, 8, Instant.parse("2026-07-03T00:00:00Z")),
                new Needs(0.0, 0.0, 0.0),
                new BehaviorProb(0.5, 0.3, 0.3)
        );
    }
}
