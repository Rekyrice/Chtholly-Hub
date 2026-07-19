package com.chtholly.agent.context.contributor;

import com.chtholly.agent.anchor.KnowledgeService;
import com.chtholly.agent.context.ContextContribution;
import com.chtholly.agent.context.ContextContributor;
import com.chtholly.agent.context.ContextOrder;
import com.chtholly.agent.context.ContextRequest;
import com.chtholly.agent.evidence.Evidence;
import com.chtholly.agent.search.HybridSearchService;
import com.chtholly.agent.search.SearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Renders known facts, semantic anchors, and hybrid search results. */
@Slf4j
@Component
public class KnowledgeContextContributor implements ContextContributor {

    private final HybridSearchService hybridSearchService;
    private final KnowledgeService knowledgeService;

    @Autowired
    public KnowledgeContextContributor(
            ObjectProvider<HybridSearchService> hybridSearchServiceProvider,
            ObjectProvider<KnowledgeService> knowledgeServiceProvider) {
        this(hybridSearchServiceProvider.getIfAvailable(),
                knowledgeServiceProvider.getIfAvailable());
    }

    public KnowledgeContextContributor(HybridSearchService hybridSearchService,
                                       KnowledgeService knowledgeService) {
        this.hybridSearchService = hybridSearchService;
        this.knowledgeService = knowledgeService;
    }

    @Override
    public String name() {
        return "knowledge";
    }

    @Override
    public int order() {
        return ContextOrder.KNOWLEDGE;
    }

    @Override
    public ContextContribution contribute(ContextRequest request) {
        StringBuilder prompt = new StringBuilder();
        boolean degraded = appendKnownFacts(prompt, request.userQuestion());
        appendSemanticAnchors(prompt, request.anchors().semantic());

        boolean retrievalRequested = isQueryIntent(request.userQuestion()) || request.evidenceRequired();
        List<Evidence> evidence = new ArrayList<>();
        if (retrievalRequested) {
            if (hybridSearchService == null) {
                degraded = true;
            } else {
                try {
                    HybridSearchService.HybridSearchResponse response =
                            hybridSearchService.hybridSearch(request.userQuestion(), 5);
                    if (response == null) {
                        degraded = true;
                    } else {
                        degraded |= response.degraded();
                        List<SearchResult> results = response.documents();
                        for (int index = 0; index < results.size(); index++) {
                            SearchResult result = results.get(index);
                            try {
                                evidence.add(Evidence.fromSearchResult(result, index + 1));
                            } catch (IllegalArgumentException exception) {
                                degraded = true;
                                log.warn("Rejected incomplete retrieval evidence: sourceId={}",
                                        result == null ? null : result.getId());
                            }
                        }
                    }
                } catch (RuntimeException exception) {
                    log.warn("Hybrid search context failed", exception);
                    degraded = true;
                }
            }
        }

        return new ContextContribution(
                name(), order(), prompt.toString(), degraded, evidence, retrievalRequested);
    }

    private boolean appendKnownFacts(StringBuilder prompt, String userQuestion) {
        if (!isAnimeKnowledgeIntent(userQuestion) || knowledgeService == null) return false;
        try {
            List<String> knowledge = knowledgeService.searchRelevantKnowledge(userQuestion, 3);
            if (knowledge == null || knowledge.isEmpty()) return false;
            appendSeparator(prompt);
            prompt.append("## 你知道的事\n\n");
            for (String item : knowledge) {
                if (hasText(item)) prompt.append("- ").append(item.trim()).append('\n');
            }
            return false;
        } catch (RuntimeException e) {
            log.warn("Knowledge base context failed", e);
            return true;
        }
    }

    private void appendSemanticAnchors(StringBuilder prompt, List<String> semantic) {
        List<String> knowledgeLines = new ArrayList<>();
        if (semantic != null) {
            for (String item : semantic) {
                if (hasText(item)) knowledgeLines.add(item.trim());
            }
        }
        if (!knowledgeLines.isEmpty()) {
            appendSeparator(prompt);
            prompt.append("## 相关知识\n\n");
            for (String item : knowledgeLines) {
                prompt.append("- ").append(item).append('\n');
            }
        }
    }

    static boolean isQueryIntent(String question) {
        if (!hasText(question)) return false;
        String text = question.trim().toLowerCase();
        return text.contains("查") || text.contains("搜") || text.contains("找")
                || text.contains("推荐") || text.contains("介绍") || text.contains("是什么")
                || text.contains("是谁") || text.contains("哪里") || text.contains("什么时候")
                || text.contains("多少") || text.contains("评分") || text.contains("角色")
                || text.contains("作品") || text.contains("资料") || text.contains("信息")
                || text.contains("search") || text.contains("find") || text.contains("what")
                || text.contains("who") || text.contains("when") || text.contains("where")
                || text.contains("recommend");
    }

    static boolean isAnimeKnowledgeIntent(String question) {
        if (!hasText(question)) return false;
        String text = question.trim().toLowerCase();
        return text.contains("动漫") || text.contains("动画") || text.contains("番剧")
                || text.contains("角色") || text.contains("故事") || text.contains("作品")
                || text.contains("主题") || text.contains("观后感") || text.contains("治愈")
                || text.contains("珂朵莉") || text.contains("芙莉莲") || text.contains("夏目")
                || text.contains("轻音") || text.contains("虫师") || text.contains("紫罗兰")
                || text.contains("clannad") || text.contains("air") || text.contains("aria")
                || text.contains("anime") || text.contains("manga");
    }

    private static void appendSeparator(StringBuilder prompt) {
        if (!prompt.isEmpty()) prompt.append("\n\n");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
