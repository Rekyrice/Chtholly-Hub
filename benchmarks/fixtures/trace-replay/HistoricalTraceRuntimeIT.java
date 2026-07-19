package com.chtholly.integration;

import com.chtholly.agent.AgentEvent;
import com.chtholly.agent.AgentTool;
import com.chtholly.agent.CharacterSoulService;
import com.chtholly.agent.ChthollyAgent;
import com.chtholly.agent.anchor.KnowledgeService;
import com.chtholly.agent.config.AgentDomainConfig;
import com.chtholly.agent.config.AgentProperties;
import com.chtholly.agent.context.ContextEngine;
import com.chtholly.agent.memory.AgentConversationMemory;
import com.chtholly.agent.memory.AgentTurn;
import com.chtholly.agent.observability.AgentExecutionTrace;
import com.chtholly.agent.observability.AgentMetrics;
import com.chtholly.agent.observability.AgentObservationService;
import com.chtholly.agent.runtime.AgentLlmInvoker;
import com.chtholly.agent.runtime.AgentLoopExecutor;
import com.chtholly.agent.runtime.AgentLoopResult;
import com.chtholly.agent.search.HybridSearchService;
import com.chtholly.agent.search.SearchResult;
import com.chtholly.agent.skill.SkillOutputValidator;
import com.chtholly.agent.skill.SkillRegistry;
import com.chtholly.agent.skill.SkillSelector;
import com.chtholly.agent.trace.TracePersistenceService;
import com.chtholly.agent.trace.TraceQueryService;
import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.common.tracing.CorrelationIdSupport;
import com.chtholly.llm.rag.RagQueryService;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.search.service.SearchService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.observation.Observation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;

/** Test-only observation overlay executed against an exact archived historical subject. */
@EnabledIfSystemProperty(named = "trace.runtime.enabled", matches = "true")
class HistoricalTraceRuntimeIT extends AbstractGoldenPathIT {

    private static final long USER_ID = 950000000000000001L;
    private static final long POST_ID = 950000000000001001L;
    @Autowired private TracePersistenceService tracePersistenceService;
    @Autowired private TraceQueryService traceQueryService;
    @Autowired private PostMapper postMapper;
    @Autowired private AgentDomainConfig domainConfig;
    @Autowired private CharacterSoulService soulService;

    @Test
    void capturesActualHistoricalRuntimeAndPersistedTrace() throws Exception {
        Path casesPath = Path.of(required("trace.runtime.cases-path"));
        Path output = Path.of(required("trace.runtime.output-dir"));
        Files.createDirectories(output);
        JsonNode cases = objectMapper.readTree(casesPath.toFile());
        assertThat(cases.isArray()).isTrue();
        for (JsonNode fixture : cases) {
            capture(fixture, output);
        }
    }

    private void capture(JsonNode fixture, Path output) throws Exception {
        String sampleId = fixture.path("sampleId").asText();
        String role = fixture.path("subjectRole").asText();
        String question = fixture.path("question").asText();
        String pageContext = fixture.path("pageContext").asText();
        assertThat(sampleId).matches("trace-replay-00[12]");
        assertThat(role).isIn("baseline", "candidate");
        prepareAuthority();

        HybridObservation retrieval = observeHybrid(question);
        AgentObservation agent = runAgent(sampleId, question, pageContext, retrieval);
        ObjectNode result = objectMapper.createObjectNode();
        result.put("sampleId", sampleId).put("subjectRole", role)
                .put("subjectCommit", required("trace.runtime.subject-commit"))
                .put("runtimeInfrastructure", "HISTORICAL_ARCHIVE_TESTCONTAINERS")
                .put("tracePersistence", "MYSQL_EXECUTION_TRACES")
                .put("traceReadback", "TRACE_QUERY_SERVICE")
                .put("observationOverlay", "TEST_ONLY_OBSERVATION_OVERLAY")
                .put("productionSourceModified", false)
                .put("runtimeTimezone", ZoneId.systemDefault().getId())
                .put("runtimeLocale", Locale.getDefault().toLanguageTag())
                .put("questionFingerprint", sha256(question))
                .put("pageContextFingerprint", sha256(pageContext))
                .put("inputFingerprint", sha256(question + "\n--page--\n" + pageContext))
                .put("traceCorrelationFingerprint", sha256(agent.correlationId()))
                .put("rawTracePayloadSha256", agent.rawPayloadSha256())
                .put("rawTracePayloadSource", "MYSQL_EXECUTION_TRACES.TRACE_PAYLOAD")
                .put("persistedTracePayloadFieldCount", agent.persistedPayloadFieldCount())
                .put("queryReadbackMatched", agent.queryReadbackMatched())
                .put("traceStatus", agent.traceStatus())
                .put("traceRowCount", agent.traceRowCount())
                .put("externalModelCalls", 0)
                .put("deterministicInvokerCalls", agent.invokerCalls())
                .put("retrievalComponent", retrieval.component())
                .put("citationValidator", agent.unsafeCitationReachedClient()
                        || agent.unsafeCitationReachedMemory()
                        ? "stream-before-validation" : "evidence-citation-gate-v1")
                .put("evidenceCount", retrieval.evidenceCount())
                .put("evidenceSnapshotHash", sha256(String.join("\n", retrieval.documentIds())))
                .put("semanticCalls", retrieval.semanticCalls())
                .put("keywordCalls", retrieval.keywordCalls())
                .put("entityCalls", retrieval.entityCalls())
                .put("entityMappingCalls", retrieval.entityMappingCalls())
                .put("unsafeCitationReachedClient", agent.unsafeCitationReachedClient())
                .put("unsafeCitationReachedMemory", agent.unsafeCitationReachedMemory());
        ObjectNode statuses = result.putObject("retrievalStatuses");
        statuses.put("semantic", retrieval.semanticCalls() == 1 ? "SUCCESS_EMPTY" : "NOT_CALLED")
                .put("keyword", retrieval.keywordCalls() == 1 ? "SUCCESS_EMPTY" : "NOT_CALLED")
                .put("entity", retrieval.entityCalls() == 1 ? "SUCCESS_RESULTS" : "NOT_CALLED");
        boolean unsafe = agent.unsafeCitationReachedClient() || agent.unsafeCitationReachedMemory();
        if (retrieval.evidenceCount() == 0) {
            result.put("failureType", "RETRIEVAL_EMPTY").put("citationValidationStatus", "NOT_RUN");
        } else if (unsafe) {
            result.put("failureType", "NONE").put("citationValidationStatus", "NOT_RUN");
        } else {
            result.put("failureType", "CITATION_INVALID").put("citationValidationStatus", "UNKNOWN_CITATION");
        }
        assertThat(agent.traceRowCount()).isEqualTo(1);
        assertThat(agent.invokerCalls()).isEqualTo(1);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                output.resolve(sampleId + "-" + role + ".json").toFile(), result);
    }

    private HybridObservation observeHybrid(String query) throws Exception {
        AtomicInteger semanticCalls = new AtomicInteger();
        AtomicInteger keywordCalls = new AtomicInteger();
        AtomicInteger entityCalls = new AtomicInteger();
        AtomicInteger entityMappingCalls = new AtomicInteger();
        SearchResult entity = new SearchResult("entity:7001", "初音未来", "实体候选", "entity", 1.0);
        FeedItemResponse feed = mock(FeedItemResponse.class, invocation -> switch (invocation.getMethod().getName()) {
            case "id" -> String.valueOf(POST_ID);
            case "title" -> "初音未来主题文章";
            case "description" -> "确定性文章";
            default -> RETURNS_DEFAULTS.answer(invocation);
        });
        PageResponse<?> empty = page(List.of());
        PageResponse<?> article = page(List.of(feed));
        RagQueryService rag = mock(RagQueryService.class, invocation -> {
            if ("search".equals(invocation.getMethod().getName())) { semanticCalls.incrementAndGet(); return List.of(); }
            return RETURNS_DEFAULTS.answer(invocation);
        });
        KnowledgeService knowledge = mock(KnowledgeService.class, invocation -> switch (invocation.getMethod().getName()) {
            case "searchEntities" -> { entityCalls.incrementAndGet(); yield List.of(entity); }
            case "searchRelevantKnowledge" -> List.of();
            default -> RETURNS_DEFAULTS.answer(invocation);
        });
        SearchService search = mock(SearchService.class, invocation -> switch (invocation.getMethod().getName()) {
            case "searchByEntityNames" -> {
                entityMappingCalls.incrementAndGet();
                assertThat(String.valueOf(invocation.getArgument(0))).contains("初音未来");
                yield article;
            }
            case "search" -> { keywordCalls.incrementAndGet(); yield empty; }
            default -> RETURNS_DEFAULTS.answer(invocation);
        });
        Constructor<?> constructor = HybridSearchService.class.getConstructors()[0];
        Object service = constructor.getParameterCount() == 3
                ? constructor.newInstance(rag, search, knowledge)
                : constructor.newInstance(rag, search, knowledge, postMapper);
        Object response = HybridSearchService.class.getMethod("hybridSearch", String.class, int.class)
                .invoke(service, query, 5);
        List<?> results = response instanceof List<?> list
                ? list : (List<?>) response.getClass().getMethod("documents").invoke(response);
        List<String> documentIds = new ArrayList<>();
        for (Object item : results) {
            Method id = method(item.getClass(), "getDocumentId", "getId");
            documentIds.add(String.valueOf(id.invoke(item)));
        }
        int evidenceCount = Math.toIntExact(documentIds.stream().filter(id -> id.startsWith("post:")).count());
        return new HybridObservation(response instanceof List<?> ? "chunk-hybrid" : "document-rrf-v1",
                List.copyOf(documentIds), evidenceCount, semanticCalls.get(), keywordCalls.get(),
                entityCalls.get(), entityMappingCalls.get());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private PageResponse<?> page(List<?> items) {
        return mock(PageResponse.class, invocation -> switch (invocation.getMethod().getName()) {
            case "items" -> items;
            case "degraded" -> false;
            default -> RETURNS_DEFAULTS.answer(invocation);
        });
    }

    private AgentObservation runAgent(
            String sampleId, String question, String pageContext, HybridObservation retrieval) throws Exception {
        AtomicInteger invokerCalls = new AtomicInteger();
        String answer = "伪造事实 [E999]";
        AgentLlmInvoker invoker = mock(AgentLlmInvoker.class, invocation -> {
            if ("stream".equals(invocation.getMethod().getName())) {
                invokerCalls.incrementAndGet();
                return Flux.just(answer);
            }
            return RETURNS_DEFAULTS.answer(invocation);
        });
        AgentLoopExecutor loop = mock(AgentLoopExecutor.class, invocation ->
                "execute".equals(invocation.getMethod().getName())
                        ? AgentLoopResult.finalReady(List.of("deterministic transcript"), 0, 0)
                        : RETURNS_DEFAULTS.answer(invocation));
        ContextEngine context = mock(ContextEngine.class, invocation -> switch (invocation.getMethod().getName()) {
            case "buildSystemPrompt" -> "deterministic context evidence=" + retrieval.evidenceCount();
            case "buildSnapshot" -> groundedSnapshot(retrieval);
            default -> RETURNS_DEFAULTS.answer(invocation);
        });
        Observation span = mock(Observation.class);
        AgentObservationService observations = mock(AgentObservationService.class, invocation ->
                Observation.class.isAssignableFrom(invocation.getMethod().getReturnType())
                        ? span : RETURNS_DEFAULTS.answer(invocation));
        List<AgentTurn> turns = new ArrayList<>();
        AgentConversationMemory memory = mock(AgentConversationMemory.class, invocation -> {
            if ("formatForPrompt".equals(invocation.getMethod().getName())) { return ""; }
            if ("add".equals(invocation.getMethod().getName())) { turns.add(invocation.getArgument(0)); return null; }
            return RETURNS_DEFAULTS.answer(invocation);
        });
        AgentProperties properties = new AgentProperties();
        properties.setMaxSteps(2); properties.setStreamCharDelayMs(0); properties.setModel("deterministic-fake");
        ChthollyAgent agent = new ChthollyAgent(invoker, loop, properties, objectMapper, List.<AgentTool>of(),
                mock(AgentMetrics.class), observations, soulService, context, tracePersistenceService,
                domainConfig, mock(SkillRegistry.class), mock(SkillSelector.class), new SkillOutputValidator());
        List<AgentEvent> events = new ArrayList<>();
        String correlationId = sha256(sampleId + required("trace.runtime.subject-commit")).substring(0, 32);
        jdbc.update("DELETE FROM execution_traces WHERE correlation_id = ?", correlationId);
        MDC.put(CorrelationIdSupport.MDC_CORRELATION_ID, correlationId);
        try { agent.run(question, USER_ID, memory, "trace-runtime", pageContext, events::add); }
        finally { MDC.clear(); }
        Object detail = awaitTrace(correlationId);
        JsonNode trace = objectMapper.valueToTree(detail);
        int rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM execution_traces WHERE correlation_id = ?", Integer.class, correlationId);
        String rawTracePayload = jdbc.queryForObject(
                "SELECT trace_payload FROM execution_traces WHERE correlation_id = ?", String.class, correlationId);
        JsonNode persistedTracePayload = objectMapper.readTree(rawTracePayload);
        assertThat(trace.path("tracePayload")).isEqualTo(persistedTracePayload);
        boolean clientUnsafe = events.stream().map(this::eventText).anyMatch(text -> text.contains("E999"));
        boolean memoryUnsafe = turns.stream().map(AgentTurn::content).anyMatch(text -> text.contains("E999"));
        return new AgentObservation(correlationId, sha256(rawTracePayload), persistedTracePayload.size(), true,
                trace.path("status").asText(), rowCount, invokerCalls.get(), clientUnsafe, memoryUnsafe);
    }

    private Object groundedSnapshot(HybridObservation retrieval) throws Exception {
        Class<?> evidence = Class.forName("com.chtholly.agent.evidence.Evidence");
        Object item = evidence.getConstructors()[0].newInstance(
                "ev-runtime", "POST", "post:" + POST_ID, "post:" + POST_ID, null,
                "初音未来主题文章", "entity", "v1", "a".repeat(64), "确定性文章", 1, 1.0,
                Set.of("PUBLIC"), "E1");
        Class<?> evidenceSet = Class.forName("com.chtholly.agent.evidence.EvidenceSet");
        if (retrieval.evidenceCount() > 0) { assertThat(retrieval.documentIds()).contains("post:" + POST_ID); }
        List<?> items = retrieval.evidenceCount() == 0 ? List.of() : List.of(item);
        Object set = evidenceSet.getMethod("of", List.class, Set.class).invoke(null, items, Set.of("PUBLIC"));
        Class<?> snapshot = Class.forName("com.chtholly.agent.context.AgentContextSnapshot");
        return snapshot.getConstructor(String.class, evidenceSet, boolean.class)
                .newInstance("deterministic context", set, true);
    }

    private Object awaitTrace(String correlationId) throws Exception {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 200; attempt++) {
            try { return traceQueryService.getTrace(correlationId); }
            catch (RuntimeException exception) { last = exception; Thread.sleep(50); }
        }
        throw new IllegalStateException("Persisted Trace was not queryable", last);
    }

    private void prepareAuthority() {
        cleanDatabase(); cleanRedis(); jdbc.update("DELETE FROM execution_traces");
        jdbc.update("INSERT INTO users (id, nickname, handle, role) VALUES (?, ?, ?, 'USER')",
                USER_ID, "Trace Runtime", "trace-runtime");
        jdbc.update("""
                INSERT INTO posts (id, tags, title, slug, description, content_etag, content_size,
                    content_sha256, creator_id, type, visible, status, publish_time, img_urls)
                VALUES (?, JSON_ARRAY('trace'), '初音未来主题文章', 'trace-runtime-post', '确定性文章',
                    ?, 16, ?, ?, 'image_text', 'public', 'published', CURRENT_TIMESTAMP, JSON_ARRAY())
                """, POST_ID, "a".repeat(64), "a".repeat(64), USER_ID);
    }

    private String eventText(AgentEvent event) {
        return event.data().path("content").asText(event.data().path("message").asText(""));
    }

    private static Method method(Class<?> type, String preferred, String fallback) throws Exception {
        try { return type.getMethod(preferred); } catch (NoSuchMethodException ignored) { return type.getMethod(fallback); }
    }

    private static String required(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) { throw new IllegalArgumentException(name + " is required"); }
        return value;
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private record HybridObservation(String component, List<String> documentIds, int evidenceCount,
                                     int semanticCalls, int keywordCalls, int entityCalls, int entityMappingCalls) { }
    private record AgentObservation(String correlationId, String rawPayloadSha256,
                                    int persistedPayloadFieldCount, boolean queryReadbackMatched, String traceStatus,
                                     int traceRowCount, int invokerCalls,
                                    boolean unsafeCitationReachedClient, boolean unsafeCitationReachedMemory) { }
}
