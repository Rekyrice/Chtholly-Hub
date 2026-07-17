package com.chtholly.llm.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PostQaPromptBuilderTest {

    @Test
    void buildsChthollyPromptWithArticleContextAndConversationHistory() {
        PostQaPromptBuilder.Prompt prompt = PostQaPromptBuilder.build(
                "你叫珂朵莉。说话安静、温和、认真。",
                List.of("久美子最终没有获得独奏。", "她仍以部长身份完成比赛。"),
                List.of(new RagConversationTurn("她赢了吗？", "没有，她在最终试奏中落选了。")),
                "那她最后为什么还能继续？"
        );

        assertThat(prompt.system())
                .contains("你叫珂朵莉")
                .contains("只能依据当前文章")
                .doesNotContain("中文知识助手");
        assertThat(prompt.user())
                .contains("久美子最终没有获得独奏")
                .contains("读者：她赢了吗？")
                .contains("珂朵莉：没有，她在最终试奏中落选了。")
                .contains("读者现在的问题：那她最后为什么还能继续？");
    }

    @Test
    void omitsConversationSectionWhenHistoryIsEmpty() {
        PostQaPromptBuilder.Prompt prompt = PostQaPromptBuilder.build(
                "你叫珂朵莉。",
                List.of("文章片段"),
                List.of(),
                "核心观点是什么？"
        );

        assertThat(prompt.user())
                .doesNotContain("此前的对话")
                .contains("读者现在的问题：核心观点是什么？");
    }
}
