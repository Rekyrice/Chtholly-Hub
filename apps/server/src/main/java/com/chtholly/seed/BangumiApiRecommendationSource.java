package com.chtholly.seed;

import com.chtholly.bangumi.client.BangumiClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Fetches launch recommendations from Bangumi API.
 */
@Component
public class BangumiApiRecommendationSource implements BangumiRecommendationSource {

    private final ObjectProvider<BangumiClient> bangumiClientProvider;

    public BangumiApiRecommendationSource(ObjectProvider<BangumiClient> bangumiClientProvider) {
        this.bangumiClientProvider = bangumiClientProvider;
    }

    @Override
    public List<BangumiSubjectSeed> fetchTopAnime(int limit) {
        BangumiClient bangumiClient = bangumiClientProvider.getIfAvailable();
        if (bangumiClient == null) {
            return List.of();
        }
        return bangumiClient.searchTopAnimeSubjects(limit)
                .map(this::parseSubjects)
                .orElseGet(List::of);
    }

    private List<BangumiSubjectSeed> parseSubjects(JsonNode root) {
        JsonNode data = root.path("data");
        if (!data.isArray()) {
            data = root;
        }
        List<BangumiSubjectSeed> subjects = new ArrayList<>();
        for (JsonNode node : data) {
            long id = node.path("id").asLong(0L);
            String title = firstText(node.path("name_cn"), node.path("name"));
            if (id == 0L || title.isBlank()) {
                continue;
            }
            subjects.add(new BangumiSubjectSeed(
                    id,
                    title,
                    firstText(node.path("name_cn"), node.path("name")),
                    firstText(node.path("images").path("large"), node.path("images").path("common")),
                    node.path("rating").path("score").asDouble(node.path("score").asDouble(0.0)),
                    node.path("summary").asText(""),
                    parseTags(node.path("tags"))));
        }
        return subjects;
    }

    private List<String> parseTags(JsonNode tagsNode) {
        if (!tagsNode.isArray()) {
            return List.of();
        }
        List<String> tags = new ArrayList<>();
        for (JsonNode tag : tagsNode) {
            String name = tag.path("name").asText("");
            if (!name.isBlank() && tags.size() < 8) {
                tags.add(name);
            }
        }
        return tags;
    }

    private static String firstText(JsonNode first, JsonNode second) {
        String value = first.asText("");
        if (!value.isBlank()) {
            return value;
        }
        return second.asText("");
    }
}
