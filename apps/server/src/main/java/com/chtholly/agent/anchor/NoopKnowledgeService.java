package com.chtholly.agent.anchor;

import com.chtholly.agent.search.SearchResult;
import com.chtholly.bangumi.model.BangumiSubjectRow;
import com.chtholly.bangumi.service.BangumiService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Placeholder semantic anchor until the dedicated knowledge base is available.
 */
@Service
public class NoopKnowledgeService implements KnowledgeService {

    private final ObjectProvider<BangumiService> bangumiServiceProvider;

    public NoopKnowledgeService(ObjectProvider<BangumiService> bangumiServiceProvider) {
        this.bangumiServiceProvider = bangumiServiceProvider;
    }

    /**
     * Returns no semantic snippets for now.
     *
     * @param userId    Authenticated user ID.
     * @param sessionId Conversation session ID.
     * @return Empty semantic context.
     */
    @Override
    public List<String> getRelevantKnowledge(long userId, String sessionId) {
        return List.of();
    }

    /**
     * Searches Bangumi subjects as entity knowledge when the graph service is available.
     *
     * @param query Query text.
     * @param topK  Maximum result count.
     * @return Bangumi entity results.
     */
    @Override
    public List<SearchResult> searchEntities(String query, int topK) {
        if (!StringUtils.hasText(query) || topK <= 0) {
            return List.of();
        }
        BangumiService bangumiService = bangumiServiceProvider.getIfAvailable();
        if (bangumiService == null) {
            return List.of();
        }

        List<BangumiSubjectRow> rows = bangumiService.search(query.trim(), topK);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }

        List<SearchResult> results = new ArrayList<>(rows.size());
        for (BangumiSubjectRow row : rows) {
            if (row == null || row.getId() == null) {
                continue;
            }
            String title = StringUtils.hasText(row.getNameCn()) ? row.getNameCn() : row.getName();
            results.add(new SearchResult(
                    "bangumi:" + row.getId(),
                    title,
                    buildSnippet(row),
                    "entity",
                    0.0));
        }
        return List.copyOf(results);
    }

    private static String buildSnippet(BangumiSubjectRow row) {
        List<String> parts = new ArrayList<>();
        if (row.getScore() != null) {
            parts.add("评分 " + row.getScore());
        }
        if (row.getRank() != null) {
            parts.add("排名 " + row.getRank());
        }
        if (row.getEpsCount() != null) {
            parts.add("集数 " + row.getEpsCount());
        }
        if (StringUtils.hasText(row.getSummary())) {
            parts.add(truncate(row.getSummary(), 140));
        }
        return String.join("；", parts);
    }

    private static String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...";
    }
}
