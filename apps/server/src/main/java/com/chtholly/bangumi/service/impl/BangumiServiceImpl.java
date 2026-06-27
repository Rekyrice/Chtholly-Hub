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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "bangumi.enabled", havingValue = "true", matchIfMissing = true)
public class BangumiServiceImpl implements BangumiService {

    private static final String API_UNAVAILABLE_USER_MSG = "Bangumi 服务暂时不可用，请稍后再试。";
    /** describePersonWorks 单次请求最多 Bangumi API 调用次数 */
    private static final int MAX_PERSON_API_CALLS = 4;

    private final BangumiSubjectMapper subjectMapper;
    private final BangumiClient bangumiClient;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;

    @Override
    public List<BangumiSubjectRow> search(String keyword, int limit) {
        String q = keyword == null ? "" : keyword.trim();
        if (q.isEmpty()) {
            return List.of();
        }
        int safeLimit = Math.min(Math.max(limit, 1), 10);

        List<BangumiSubjectRow> local = inReadTransaction(() -> searchLocal(q, safeLimit));
        if (!local.isEmpty()) {
            return local;
        }

        JsonNode resp = fetchSearchResponse(q, safeLimit);
        List<BangumiSubjectRow> fetched = mapSubjectsFromApi(resp == null ? null : resp.get("data"), false);
        inWriteTransaction(() -> persistSubjects(fetched, "search_upsert"));

        List<BangumiSubjectRow> refreshed = inReadTransaction(() -> searchLocal(q, safeLimit));
        if (!refreshed.isEmpty()) {
            return refreshed;
        }

        return inReadTransaction(() -> loadPersistedFallback(fetched, safeLimit));
    }

    @Override
    public List<BangumiSubjectRow> searchAnimeSeries(String keyword, int limit) {
        String q = keyword == null ? "" : keyword.trim();
        if (q.isEmpty()) {
            return List.of();
        }
        int safeLimit = Math.min(Math.max(limit, 1), 20);

        JsonNode resp = fetchSearchResponse(q, safeLimit);
        Map<Long, BangumiSubjectRow> merged = new LinkedHashMap<>();
        for (BangumiSubjectRow row : mapSubjectsFromApi(resp == null ? null : resp.get("data"), true)) {
            merged.putIfAbsent(row.getId(), row);
        }

        List<BangumiSubjectRow> rows = new ArrayList<>(merged.values());
        inWriteTransaction(() -> {
            for (BangumiSubjectRow row : rows) {
                subjectMapper.upsert(row);
            }
        });

        rows.sort((a, b) -> {
            if (a.getAirDate() == null && b.getAirDate() == null) {
                return Long.compare(a.getId(), b.getId());
            }
            if (a.getAirDate() == null) {
                return 1;
            }
            if (b.getAirDate() == null) {
                return -1;
            }
            return a.getAirDate().compareTo(b.getAirDate());
        });
        return rows;
    }

    @Override
    public String describePersonWorks(String keyword, String workTitleHint, String workType) {
        Integer typeFilter = parseWorkTypeFilter(workType);
        Map<Long, String> personNames = new LinkedHashMap<>();
        Set<Long> personIds = new LinkedHashSet<>();
        ApiCallBudget budget = new ApiCallBudget(MAX_PERSON_API_CALLS);
        Map<Long, String> worksCache = new LinkedHashMap<>();

        if (StringUtils.hasText(keyword)) {
            collectPersonIdsFromSearch(personIds, personNames, keyword.trim(), budget);
        }

        String workHint = StringUtils.hasText(workTitleHint) ? workTitleHint.trim() : null;
        if (workHint != null) {
            collectPersonIdsFromWork(personIds, personNames, workHint, budget);
        }

        if (personIds.isEmpty()) {
            return "Bangumi 未找到与「" + nullToEmpty(keyword) + "」相关的人物。";
        }

        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (Long personId : personIds) {
            if (shown >= 2 || !budget.hasRemaining()) {
                break;
            }
            String block = formatOnePersonWorks(personId, personNames.get(personId), typeFilter, budget, worksCache);
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

    @Override
    public String describeSubjectCharacters(String keyword) {
        String q = keyword == null ? "" : keyword.trim();
        if (q.isEmpty()) {
            return "错误：缺少条目 keyword";
        }

        List<BangumiSubjectRow> subjects = search(q, 3);
        if (subjects.isEmpty()) {
            return "Bangumi 未找到与「" + q + "」相关的条目，无法查询角色。";
        }

        BangumiSubjectRow subject = subjects.get(0);
        JsonNode characters = bangumiClient.getSubjectCharacters(subject.getId());
        if (characters == null) {
            throw apiUnavailable("getSubjectCharacters subjectId=" + subject.getId());
        }
        if (!characters.isArray() || characters.isEmpty()) {
            return "条目《" + displayName(subject) + "》暂无角色数据。";
        }

        List<String> lines = new ArrayList<>();
        for (JsonNode c : characters) {
            String name = text(c, "name_cn");
            if (!StringUtils.hasText(name)) {
                name = text(c, "name");
            }
            String relation = text(c, "relation");
            if (!StringUtils.hasText(name)) {
                continue;
            }
            lines.add("- " + name + (StringUtils.hasText(relation) ? "（" + relation + "）" : ""));
        }

        if (lines.isEmpty()) {
            return "条目《" + displayName(subject) + "》暂无可用角色名。";
        }

        return "条目：《" + displayName(subject) + "》[Bangumi " + subject.getId() + "]\n"
                + "登场角色（共 " + lines.size() + " 个）：\n"
                + String.join("\n", lines);
    }

    /** 本地 DB 读（短事务）。 */
    private <T> T inReadTransaction(Supplier<T> action) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        return template.execute(status -> action.get());
    }

    /** 本地 DB 写（独立短事务，不含 HTTP）。 */
    private void inWriteTransaction(Runnable action) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.executeWithoutResult(status -> action.run());
    }

    private JsonNode fetchSearchResponse(String keyword, int limit) {
        assertNoActiveTransaction();
        JsonNode resp = bangumiClient.searchSubjects(keyword, limit);
        if (resp == null) {
            throw apiUnavailable("searchSubjects keyword=" + keyword);
        }
        return resp;
    }

    private List<BangumiSubjectRow> mapSubjectsFromApi(JsonNode data, boolean animeOnly) {
        List<BangumiSubjectRow> result = new ArrayList<>();
        if (data == null || !data.isArray()) {
            return result;
        }
        for (JsonNode item : data) {
            if (animeOnly && item.path("type").asInt(0) != 2) {
                continue;
            }
            try {
                BangumiSubjectRow row = mapSubject(item);
                if (row.getEpsCount() == null) {
                    row.setEpsCount(fetchEpisodeTotal(row.getId()));
                }
                result.add(row);
            } catch (Exception e) {
                log.warn("Bangumi 条目映射失败: {}", e.getMessage());
            }
        }
        return result;
    }

    private void persistSubjects(List<BangumiSubjectRow> rows, String syncAction) {
        for (BangumiSubjectRow row : rows) {
            try {
                subjectMapper.upsert(row);
                subjectMapper.insertSyncLog(row.getId(), syncAction);
            } catch (Exception e) {
                log.warn("Bangumi 条目回填失败 id={}: {}", row.getId(), e.getMessage());
            }
        }
    }

    private List<BangumiSubjectRow> loadPersistedFallback(List<BangumiSubjectRow> fetched, int safeLimit) {
        List<BangumiSubjectRow> fallback = new ArrayList<>();
        for (BangumiSubjectRow row : fetched) {
            BangumiSubjectRow db = subjectMapper.findById(row.getId());
            if (db != null) {
                fallback.add(db);
            }
            if (fallback.size() >= safeLimit) {
                break;
            }
        }
        if (!fallback.isEmpty()) {
            return fallback;
        }
        return fetched.size() > safeLimit ? fetched.subList(0, safeLimit) : fetched;
    }

    private String displayName(BangumiSubjectRow row) {
        if (row.getNameCn() != null && !row.getNameCn().isBlank()) {
            return row.getNameCn() + "（" + row.getName() + "）";
        }
        return row.getName();
    }

    private void collectPersonIdsFromSearch(
            Set<Long> personIds, Map<Long, String> personNames, String keyword, ApiCallBudget budget) {
        if (!budget.consume()) {
            log.warn("Bangumi person API budget exhausted before searchPersons keyword={}", keyword);
            return;
        }
        JsonNode resp = bangumiClient.searchPersons(keyword, null, 5);
        if (resp == null) {
            throw apiUnavailable("searchPersons keyword=" + keyword);
        }
        appendPersonNodes(personIds, personNames, resp.path("data"));
        if (personIds.isEmpty() && budget.consume()) {
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

    private void collectPersonIdsFromWork(
            Set<Long> personIds, Map<Long, String> personNames, String workTitle, ApiCallBudget budget) {
        List<BangumiSubjectRow> subjects = search(workTitle, 1);
        if (subjects.isEmpty()) {
            return;
        }
        if (!budget.consume()) {
            log.warn("Bangumi person API budget exhausted before getSubjectPersons work={}", workTitle);
            return;
        }
        BangumiSubjectRow subject = subjects.get(0);
        JsonNode persons = bangumiClient.getSubjectPersons(subject.getId());
        if (persons == null || !persons.isArray()) {
            return;
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

    private String formatOnePersonWorks(
            long personId,
            String personName,
            Integer typeFilter,
            ApiCallBudget budget,
            Map<Long, String> worksCache) {
        if (worksCache.containsKey(personId)) {
            return worksCache.get(personId);
        }
        if (!budget.consume()) {
            log.warn("Bangumi person API budget exhausted before getPersonSubjects personId={}", personId);
            return null;
        }
        JsonNode works = bangumiClient.getPersonSubjects(personId);
        if (works == null) {
            throw apiUnavailable("getPersonSubjects personId=" + personId);
        }
        if (!works.isArray() || works.isEmpty()) {
            worksCache.put(personId, null);
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
            worksCache.put(personId, null);
            return null;
        }

        String filterNote = typeFilter == null ? "全部" : subjectTypeLabel(typeFilter);
        String block = "人物：" + personName + " [Bangumi " + personId + "]\n"
                + "参与作品（" + filterNote + "，共 " + lines.size() + " 部）：\n"
                + String.join("\n", lines);
        worksCache.put(personId, block);
        return block;
    }

    private IllegalStateException apiUnavailable(String context) {
        log.error("Bangumi API unavailable during {} (check network/proxy, BANGUMI_HTTP_PROXY in env)", context);
        return new IllegalStateException(API_UNAVAILABLE_USER_MSG);
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
        assertNoActiveTransaction();
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

    /** 单次 describePersonWorks 请求的 Bangumi API 调用配额。 */
    private static final class ApiCallBudget {
        private int remaining;

        private ApiCallBudget(int max) {
            this.remaining = max;
        }

        private boolean consume() {
            if (remaining <= 0) {
                return false;
            }
            remaining--;
            return true;
        }

        private boolean hasRemaining() {
            return remaining > 0;
        }
    }

    /** 供单元测试验证 HTTP 调用时未持有 Spring 事务。 */
    static void assertNoActiveTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("HTTP call must not run inside a DB transaction");
        }
    }
}
