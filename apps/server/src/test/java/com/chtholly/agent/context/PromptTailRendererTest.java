package com.chtholly.agent.context;

import com.chtholly.agent.AgentTool;
import com.chtholly.agent.ParamDef;
import com.chtholly.agent.memory.AgentTurn;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PromptTailRendererTest {

    private final PromptTailRenderer renderer = new PromptTailRenderer();

    @Test
    void rendersCompleteToolProtocolExactly() {
        assertThat(renderer.renderTools(List.of(normalTool()))).isEqualTo("""
                ## 可用工具

                ### lookup
                查询资料
                  参数：
                    - keyword (string, 必填): 关键词
                    - limit (integer, 可选): 数量

                ## 工具使用准则

                1. 优先用工具获取事实，不确定时查一下再回答
                2. 每次只调用一个工具，等结果返回后再决定下一步
                3. 如果站内搜索无结果，尝试 Bangumi 工具搜索动漫相关内容
                4. 不要编造工具返回的数据，如实告诉用户查询结果

                输出格式：只输出单个 JSON 对象；调用工具用 {"action":"工具名","input":{...}}，可以回答时用 {"action":"final","answer":"占位"}""");
    }

    @Test
    void skipsNullAndBrokenToolsButKeepsHealthyToolAndProtocol() {
        String rendered = renderer.renderTools(java.util.Arrays.asList(null, brokenTool(), normalTool()));

        assertThat(rendered)
                .contains("### lookup", "查询资料")
                .doesNotContain("### broken")
                .contains("## 工具使用准则")
                .contains("每次只调用一个工具")
                .contains("{\"action\":\"工具名\",\"input\":{...}}")
                .contains("{\"action\":\"final\",\"answer\":\"占位\"}");
    }

    @Test
    void iteratorFailureStillKeepsFixedProtocol() {
        Iterable<AgentTool> brokenIterable = () -> {
            throw new IllegalStateException("iterator unavailable");
        };

        assertThat(renderer.renderTools(brokenIterable))
                .contains("## 可用工具")
                .contains("## 工具使用准则")
                .contains("{\"action\":\"final\",\"answer\":\"占位\"}");
    }

    @Test
    void formattedHistoryTakesPriorityExactly() {
        assertThat(renderer.renderHistory(
                "  User: formatted\nAssistant: response  ",
                List.of(AgentTurn.user("anchor"))))
                .isEqualTo("## 对话历史\n\nUser: formatted\nAssistant: response");
    }

    @Test
    void episodicHistoryIsUsedWhenFormattedHistoryIsBlankExactly() {
        assertThat(renderer.renderHistory(" ", List.of(
                AgentTurn.user(" question "),
                AgentTurn.assistant(" answer "))))
                .isEqualTo("## 对话历史\n\nUser: question\nAssistant: answer");
    }

    @Test
    void emptyHistoryUsesPlaceholderExactly() {
        assertThat(renderer.renderHistory("", List.of()))
                .isEqualTo("## 对话历史\n\n（暂无）");
    }

    private AgentTool normalTool() {
        return new AgentTool() {
            @Override
            public String name() {
                return "lookup";
            }

            @Override
            public String description() {
                return "查询资料";
            }

            @Override
            public Map<String, ParamDef> parameterSchema() {
                Map<String, ParamDef> schema = new LinkedHashMap<>();
                schema.put("keyword", new ParamDef("关键词", String.class, true));
                schema.put("limit", new ParamDef("数量", Integer.class, false));
                return schema;
            }

            @Override
            public String execute(Map<String, Object> input, long userId) {
                return "result";
            }
        };
    }

    private AgentTool brokenTool() {
        return new AgentTool() {
            @Override
            public String name() {
                return "broken";
            }

            @Override
            public String description() {
                throw new IllegalStateException("description unavailable");
            }

            @Override
            public String execute(Map<String, Object> input, long userId) {
                return "unused";
            }
        };
    }
}
