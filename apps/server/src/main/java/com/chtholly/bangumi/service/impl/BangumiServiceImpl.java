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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

        List<BangumiSubjectRow> local = searchLocal(q, safeLimit);
        if (!local.isEmpty()) {
            return local;
        }

        JsonNode resp = bangumiClient.searchSubjects(q, safeLimit);
        if (resp == null) {
            throw new IllegalStateException(
                    "Bangumi API 无法访问。请确认 VPN/代理已开启，并在 .env 设置 BANGUMI_HTTP_PROXY（如 http://127.0.0.1:7897）");
        }
        if (!resp.has("data") || !resp.get("data").isArray()) {
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

        List<BangumiSubjectRow> refreshed = searchLocal(q, safeLimit);
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

    @Override
    public String describePersonWorks(String keyword, String workTitleHint, String workType) {
        Integer typeFilter = parseWorkTypeFilter(workType);
        Map<Long, String> personNames = new LinkedHashMap<>();
        Set<Long> personIds = new LinkedHashSet<>();

        if (StringUtils.hasText(keyword)) {
            collectPersonIdsFromSearch(personIds, personNames, keyword.trim());
        }

        String workHint = StringUtils.hasText(workTitleHint) ? workTitleHint.trim() : null;
        if (workHint != null) {
            collectPersonIdsFromWork(personIds, personNames, workHint);
        }

        if (personIds.isEmpty()) {
            return "Bangumi 未找到与「" + nullToEmpty(keyword) + "」相关的人物。";
        }

        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (Long personId : personIds) {
            if (shown >= 2) {
                break;
            }
            String block = formatOnePersonWorks(personId, personNames.get(personId), typeFilter);
            if (block != null && !block.isBlank()) {
                if (!out.isEmpty()) {
                    out.append("\n\n");
                }
                out.append(block);
                shown++;
            }
        }
        return out.isEmpty() ? "未找到该人物参与的作品。" : out.toString();
    }

    private void collectPersonIdsFromSearch(Set<Long> personIds, Map<Long, String> personNames, String keyword) {
        // career 多值在 Bangumi API 中是「且」关系，不可传多个；先无 filter 搜索
        JsonNode resp = bangumiClient.searchPersons(keyword, null, 5);
        if (resp == null) {
            throw new IllegalStateException(
                    "Bangumi API 无法访问。请确认 VPN/代理已开启，并在 .env 设置 BANGUMI_HTTP_PROXY（如 http://127.0.0.1:7897）");
        }
        appendPersonNodes(personIds, personNames, resp.path("data"));
        if (personIds.isEmpty()) {
            resp = bangumiClient.searchPersons(keyword, List.of("mangaka"), 5);
            if (resp != null) {
                appendPersonNodes(personIds, personNames, resp.path("data"));
            }
        }
    }

    private void appendPersonNodes(Set<Long> personIds, Map<Long, String> personNames, JsonNode data) {
        if (data == null || !data.isArray()) {
            return;
        }
        for (JsonNode p : data) {
            long id = p.path("id").asLong(0);
            if (id > 0) {
                personIds.add(id);
                String name = text(p, "name");
                if (!StringUtils.hasText(name)) {
                    name = text(p, "name_cn");
                }
                personNames.putIfAbsent(id, name);
            }
        }
    }

    private void collectPersonIdsFromWork(Set<Long> personIds, Map<Long, String> personNames, String workTitle) {
        List<BangumiSubjectRow> subjects = search(workTitle, 3);
        for (BangumiSubjectRow subject : subjects) {
            JsonNode persons = bangumiClient.getSubjectPersons(subject.getId());
            if (persons == null || !persons.isArray()) {
                continue;
            }
            for (JsonNode p : persons) {
                if (!isCreatorPerson(p)) {
                    continue;
                }
                long id = p.path("id").asLong(0);
                if (id > 0) {
                    personIds.add(id);
                    String name = text(p, "name");
                    if (!StringUtils.hasText(name)) {
                        name = text(p, "name_cn");
                    }
                    personNames.putIfAbsent(id, name);
                }
            }
        }
    }

    /** 条目关联人物用 relation；人物作品列表用 staff。 */
    private boolean isCreatorPerson(JsonNode p) {
        String role = text(p, "staff");
        if (!StringUtils.hasText(role)) {
            role = text(p, "relation");
        }
        if (StringUtils.hasText(role)) {
            if (role.contains("作者") || role.contains("原作") || role.contains("漫画")) {
                return true;
            }
        }
        JsonNode careers = p.get("career");
        if (careers != null && careers.isArray()) {
            for (JsonNode c : careers) {
                String v = c.asText("");
                if ("mangaka".equals(v) || "writer".equals(v) || "illustrator".equals(v)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String formatOnePersonWorks(long personId, String personName, Integer typeFilter) {
        JsonNode works = bangumiClient.getPersonSubjects(personId);
        if (works == null) {
            throw new IllegalStateException(
                    "Bangumi API 无法访问。请确认 VPN/代理已开启，并在 .env 设置 BANGUMI_HTTP_PROXY（如 http://127.0.0.1:7897）");
        }
        if (!works.isArray() || works.isEmpty()) {
            return null;
        }

        if (!StringUtils.hasText(personName)) {
            personName = "人物 " + personId;
        }

        List<String> lines = new ArrayList<>();
        Set<Long> seenIds = new LinkedHashSet<>();
        for (JsonNode w : works) {
            long workId = w.path("id").asLong(0);
            if (workId > 0 && !seenIds.add(workId)) {
                continue;
            }
            int type = w.path("type").asInt(0);
            if (typeFilter != null && type != typeFilter) {
                continue;
            }
            String name = text(w, "name_cn");
            if (!StringUtils.hasText(name)) {
                name = text(w, "name");
            }
            String role = text(w, "staff");
            if (!StringUtils.hasText(role)) {
                role = text(w, "relation");
            }
            String typeLabel = subjectTypeLabel(type);
            lines.add("- 《" + nullToEmpty(name) + "》[" + typeLabel + "]"
                    + (StringUtils.hasText(role) ? "（" + role + "）" : "")
                    + " [Bangumi " + w.path("id").asLong() + "]");
        }

        if (lines.isEmpty()) {
            return null;
        }

        String filterNote = typeFilter == null ? "全部" : subjectTypeLabel(typeFilter);
        return "人物：" + personName + " [Bangumi " + personId + "]\n"
                + "参与作品（" + filterNote + "，共 " + lines.size() + " 部）：\n"
                + String.join("\n", lines);
    }

    private Integer parseWorkTypeFilter(String workType) {
        if (!StringUtils.hasText(workType) || "all".equalsIgnoreCase(workType)) {
            return null;
        }
        if ("book".equalsIgnoreCase(workType) || "manga".equalsIgnoreCase(workType) || "漫画".equals(workType)) {
            return 1;
        }
        if ("anime".equalsIgnoreCase(workType) || "动画".equals(workType)) {
            return 2;
        }
        return null;
    }

    private String subjectTypeLabel(int type) {
        return switch (type) {
            case 1 -> "书籍";
            case 2 -> "动画";
            case 3 -> "音乐";
            case 4 -> "游戏";
            case 6 -> "三次元";
            default -> "类型" + type;
        };
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private List<BangumiSubjectRow> searchLocal(String keyword, int limit) {
        try {
            List<BangumiSubjectRow> hits = subjectMapper.searchByKeyword(keyword, limit);
            if (!hits.isEmpty()) {
                return hits;
            }
            return subjectMapper.searchByKeywordLike(keyword, limit);
        } catch (Exception e) {
            log.warn("Bangumi 本地检索失败: {}", e.getMessage());
            return List.of();
        }
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
