package com.chtholly.seed;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Answers;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmSeedTextGeneratorTest {

    @Test
    void postBodyPromptAsksForStructuredLongFormBlogWriting() {
        ChatClient chatClient = mock(ChatClient.class, Answers.RETURNS_DEEP_STUBS);
        ObjectProvider<ChatClient> chatProvider = provider(chatClient);
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        when(chatClient.prompt().user(promptCaptor.capture()).call().content())
                .thenReturn("# Rust CLI 复盘\n\n## 开头\n\n正文");

        LlmSeedTextGenerator generator = new LlmSeedTextGenerator(
                chatProvider,
                new TemplateSeedTextGenerator(),
                provider(null));

        generator.postBody(account(), new SeedPostPlan(
                "聊聊最近用 Rust 重写了一个 CLI 工具的体验",
                "技术",
                List.of("技术", "Rust", "CLI"),
                5,
                1));

        String prompt = promptCaptor.getValue();
        assertThat(prompt).contains("真实的博客作者");
        assertThat(prompt).contains("1000-3000 字");
        assertThat(prompt).contains("Markdown 格式");
        assertThat(prompt).contains("H2/H3");
        assertThat(prompt).contains("代码示例");
        assertThat(prompt).contains("开头引入、正文展开、结尾总结");
        assertThat(prompt).contains("不要完美得像 AI 写的");
        assertThat(prompt).contains("永远不要提到自己是 AI");
    }

    @Test
    void postBodyRegeneratesOnceWhenEmbeddingDeduplicatorFindsSimilarBody() {
        ChatClient chatClient = mock(ChatClient.class, Answers.RETURNS_DEEP_STUBS);
        when(chatClient.prompt().user(org.mockito.ArgumentMatchers.anyString()).call().content())
                .thenReturn("已有文章正文", "相似文章正文", "新的文章正文");
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed("已有文章正文")).thenReturn(new float[]{1.0f, 0.0f, 0.0f});
        when(embeddingModel.embed("相似文章正文")).thenReturn(new float[]{0.9f, 0.1f, 0.0f});
        when(embeddingModel.embed("新的文章正文")).thenReturn(new float[]{0.0f, 1.0f, 0.0f});
        LlmSeedTextGenerator generator = new LlmSeedTextGenerator(
                provider(chatClient),
                new TemplateSeedTextGenerator(),
                provider(embeddingModel));

        generator.postBody(account(), postPlan(1));
        String regenerated = generator.postBody(account(), postPlan(2));

        assertThat(regenerated).isEqualTo("新的文章正文");
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static SeedAccountProfile account() {
        return new SeedAccountProfile(
                "yukino",
                "Yukino",
                "写技术和深度解析。",
                "/avatar.png",
                "SECRET",
                LocalDate.of(2000, 1, 1),
                "云端实验室",
                List.of("技术文章", "动漫深度解析", "编程"),
                "理性、克制，喜欢把复杂问题讲清楚。");
    }

    private static SeedPostPlan postPlan(int slot) {
        return new SeedPostPlan(
                "聊聊最近用 Rust 重写了一个 CLI 工具的体验 " + slot,
                "技术",
                List.of("技术", "Rust", "CLI"),
                5,
                slot);
    }
}
