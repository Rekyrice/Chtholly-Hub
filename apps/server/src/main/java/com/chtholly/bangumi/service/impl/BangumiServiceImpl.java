package com.chtholly.bangumi.service.impl;

import com.chtholly.bangumi.client.BangumiClient;
import com.chtholly.bangumi.mapper.BangumiSubjectMapper;
import com.chtholly.bangumi.model.BangumiSubjectRow;
import com.chtholly.bangumi.service.BangumiService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "bangumi.enabled", havingValue = "true", matchIfMissing = true)
public class BangumiServiceImpl implements BangumiService {

    private final BangumiSubjectMapper subjectMapper;
    private final BangumiClient bangumiClient;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public List<BangumiSubjectRow> search(String keyword, int limit) {
        String q = keyword == null ? "" : keyword.trim();
        if (q.isEmpty()) {
            return List.of();
        }
        int safeLimit = Math.min(Math.max(limit, 1), 10);

        List<BangumiSubjectRow> local = subjectMapper.searchByKeyword(q, safeLimit);
        if (!local.isEmpty()) {
            return local;
        }

        JsonNode resp = bangumiClient.searchSubjects(q, safeLimit);
        if (resp == null || !resp.has("data") || !resp.get("data").isArray()) {
            return List.of();
        }

        for (JsonNode item : resp.get("data")) {
            try {
                BangumiSubjectRow row = mapSubject(item);
                if (row.getEpsCount() == null) {
                    row.setEpsCount(fetchEpisodeTotal(row.getId()));
                }
                subjectMapper.upsert(row);
                subjectMapper.insertSyncLog(row.getId(), "search_upsert");
            } catch (Exception e) {
                log.warn("Bangumi 条目回填失败: {}", e.getMessage());
            }
        }

        List<BangumiSubjectRow> refreshed = subjectMapper.searchByKeyword(q, safeLimit);
        if (!refreshed.isEmpty()) {
            return refreshed;
        }

        // FULLTEXT 对短词可能无命中，直接返回刚写入的数据
        List<BangumiSubjectRow> fallback = new ArrayList<>();
        for (JsonNode item : resp.get("data")) {
            long id = item.path("id").asLong(0);
            if (id > 0) {
                BangumiSubjectRow row = subjectMapper.findById(id);
                if (row != null) {
                    fallback.add(row);
                }
            }
            if (fallback.size() >= safeLimit) {
                break;
            }
        }
        return fallback;
    }

    private Integer fetchEpisodeTotal(long subjectId) {
        JsonNode epResp = bangumiClient.listEpisodes(subjectId, 1);
        if (epResp == null) {
            return null;
        }
        int total = epResp.path("total").asInt(-1);
        return total >= 0 ? total : null;
    }

    private BangumiSubjectRow mapSubject(JsonNode node) throws Exception {
        BangumiSubjectRow row = new BangumiSubjectRow();
        row.setId(node.path("id").asLong());
        row.setType(node.path("type").asInt(2));
        row.setName(text(node, "name"));
        row.setNameCn(text(node, "name_cn"));
        row.setSummary(text(node, "summary"));
        row.setNsfw(node.path("nsfw").asBoolean(false));
        row.setAirDate(parseDate(text(node, "date")));
        if (node.has("rating") && node.get("rating").has("score")) {
            double sc = node.get("rating").get("score").asDouble(0);
            if (sc > 0) {
                row.setScore(BigDecimal.valueOf(sc));
            }
        }
        if (node.has("rank") && !node.get("rank").isNull()) {
            row.setRank(node.get("rank").asInt());
        }
        row.setRawJson(objectMapper.writeValueAsString(node));
        return row;
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText();
        return StringUtils.hasText(s) ? s : null;
    }

    private LocalDate parseDate(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
