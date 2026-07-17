package com.chtholly.llm.rag;

import com.chtholly.agent.CharacterSoulService;
import com.chtholly.agent.search.SearchResult;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 问答查询服务：
 * - 在问答前保障索引，检索相关上下文并构造提示词
 * - 通过 ChatClient 以流式（SSE）方式返回模型输出
 */
@Service
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
@RequiredArgsConstructor
public class RagQueryService {
    // 向量检索接口（Elasticsearch 向量库封装）
    private final VectorStore vectorStore;
    // 大模型对话客户端（在 LlmConfig 中通过 @Qualifier 绑定 deepSeekChatModel）
    private final ChatClient chatClient;
    // 索引服务：确保帖子在问答前已建立/更新索引
    private final RagIndexService indexService;
    // 统一角色设定：文章问答与完整 Agent 使用同一份珂朵莉人格
    private final CharacterSoulService characterSoulService;

    /**
     * Searches indexed post chunks and returns unified agent search results.
     *
     * @param query Query text.
     * @param topK  Maximum result count.
     * @return Semantic post chunk results.
     */
    public List<SearchResult> search(String query, int topK) {
        if (query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder().query(query.trim()).topK(topK).build()
        );
        List<SearchResult> results = new ArrayList<>(docs.size());
        for (Document doc : docs) {
            Object postId = doc.getMetadata().get("postId");
            String id = postId == null ? "semantic:" + results.size() : "post:" + postId;
            String title = stringValue(doc.getMetadata().get("title"), "帖子片段");
            String snippet = truncate(doc.getText(), 240);
            results.add(new SearchResult(id, title, snippet, "semantic", 0.0));
        }
        return List.copyOf(results);
    }

    /**
     * 使用 WebFlux 返回回答内容的流。
     */
    public Flux<String> streamAnswerFlux(long postId, String question, int topK, int maxTokens) {
        return streamAnswerFlux(postId, question, List.of(), topK, maxTokens);
    }

    /**
     * Streams a post-scoped answer with local article conversation history.
     *
     * @param postId current post ID
     * @param question current reader question
     * @param history completed turns from this article page
     * @param topK maximum retrieved chunk count
     * @param maxTokens maximum generated tokens
     * @return answer fragments
     */
    public Flux<String> streamAnswerFlux(long postId,
                                         String question,
                                         List<RagConversationTurn> history,
                                         int topK,
                                         int maxTokens) {
        // 轻量保障：如索引不存在或指纹未变更则跳过，否则重建
        indexService.ensureIndexed(postId);

        // 检索上下文：先宽召回，再按 postId 做服务端过滤
        List<String> contexts = searchContexts(String.valueOf(postId), question, Math.max(1, topK));
        PostQaPromptBuilder.Prompt prompt = PostQaPromptBuilder.build(
                characterSoulService.getSoulContent(), contexts, history, question);

        return chatClient
                .prompt() // 构建对话
                .system(prompt.system())
                .user(prompt.user())
                .options(DeepSeekChatOptions.builder()
                        .model("deepseek-chat") // 指定 DeepSeek 模型
                        .temperature(0.2)       // 低温度：更稳健、少发散
                        .maxTokens(maxTokens)    // 控制最大输出长度
                        .build())
                .stream()  // 以流式（SSE）返回模型输出
                .content(); // 转换为 Flux<String>
    }

    /**
     * 语义检索上下文：
     * - 先进行宽召回（fetchK ≥ 3×topK，至少 20）提高召回率
     * - 再按 metadata.postId 做服务端过滤，避免跨帖子污染
     */
    private List<String> searchContexts(String postId, String query, int topK) {
        int fetchK = Math.max(topK * 3, 20); // 宽召回：扩大初始检索集合
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(fetchK).build() // 语义相似检索
        );
        List<String> out = new ArrayList<>(topK);
        for (Document d : docs) {
            Object pid = d.getMetadata().get("postId");
            if (pid != null && postId.equals(String.valueOf(pid))) { // 仅保留当前帖子对应的切片
                String txt = d.getText();
                if (txt != null && !txt.isEmpty()) {
                    out.add(txt);
                    if (out.size() >= topK) break; // 只取前 topK 个上下文
                }
            }
        }
        return out;
    }

    private static String stringValue(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }

    private static String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...";
    }
}
