package com.chtholly.agent.trace;

import com.chtholly.agent.trace.dto.TraceStatsDto;
import com.chtholly.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TraceQueryServiceTest {

    @Mock
    private TraceMapper traceMapper;
    @Mock
    private FailurePatternMapper failurePatternMapper;

    private ObjectMapper objectMapper;
    private TraceQueryService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new TraceQueryService(traceMapper, failurePatternMapper, objectMapper);
    }

    @Test
    void getStatsAggregatesCountsAndP95() {
        when(traceMapper.countSince(any())).thenReturn(10L);
        when(traceMapper.countByStatusSince(eq(TraceStatus.SUCCESS.name()), any())).thenReturn(8L);
        when(traceMapper.countByStatusSince(eq(TraceStatus.FAILURE.name()), any())).thenReturn(1L);
        when(traceMapper.countByStatusSince(eq(TraceStatus.TIMEOUT.name()), any())).thenReturn(1L);
        when(traceMapper.countByStatusSince(eq(TraceStatus.ABORTED.name()), any())).thenReturn(0L);
        when(traceMapper.avgDurationSince(any())).thenReturn(2300.0);
        when(traceMapper.listDurationsSince(any(), anyInt())).thenReturn(List.of(100, 200, 300, 400, 5000));
        when(failurePatternMapper.listAllOrderByCountDesc(5)).thenReturn(List.of());
        TraceTokenTrendRow trend = new TraceTokenTrendRow();
        trend.setDay(LocalDate.now());
        trend.setInputTokens(100L);
        trend.setOutputTokens(50L);
        when(traceMapper.tokenTrendSince(any())).thenReturn(List.of(trend));

        TraceStatsDto stats = service.getStats(7);

        assertThat(stats.totalExecutions()).isEqualTo(10);
        assertThat(stats.successCount()).isEqualTo(8);
        assertThat(stats.successRate()).isEqualTo(80.0);
        assertThat(stats.avgDurationMs()).isEqualTo(2300.0);
        assertThat(stats.p95DurationMs()).isEqualTo(5000);
        assertThat(stats.tokenTrend()).hasSize(1);
    }

    @Test
    void getStatsBetweenAggregatesCountsAndP95WithinRange() {
        Instant from = Instant.parse("2026-07-01T00:00:00Z");
        Instant to = Instant.parse("2026-07-09T00:00:00Z");
        when(traceMapper.countBetween(from, to)).thenReturn(4L);
        when(traceMapper.countByStatusBetween(TraceStatus.SUCCESS.name(), from, to)).thenReturn(3L);
        when(traceMapper.countByStatusBetween(TraceStatus.FAILURE.name(), from, to)).thenReturn(1L);
        when(traceMapper.countByStatusBetween(TraceStatus.TIMEOUT.name(), from, to)).thenReturn(0L);
        when(traceMapper.countByStatusBetween(TraceStatus.ABORTED.name(), from, to)).thenReturn(0L);
        when(traceMapper.avgDurationBetween(from, to)).thenReturn(1200.0);
        when(traceMapper.listDurationsBetween(from, to, 5000)).thenReturn(List.of(100, 200, 1000, 5000));
        when(failurePatternMapper.listBetweenOrderByCountDesc(from, to, 5)).thenReturn(List.of());
        when(traceMapper.tokenTrendBetween(from, to)).thenReturn(List.of());

        TraceStatsDto stats = service.getStats(from, to);

        assertThat(stats.totalExecutions()).isEqualTo(4);
        assertThat(stats.successRate()).isEqualTo(75.0);
        assertThat(stats.avgDurationMs()).isEqualTo(1200.0);
        assertThat(stats.p95DurationMs()).isEqualTo(5000);
        verify(traceMapper).countBetween(from, to);
    }

    @Test
    void getFailurePatternsBetweenUsesLastSeenRange() {
        Instant from = Instant.parse("2026-07-01T00:00:00Z");
        Instant to = Instant.parse("2026-07-09T00:00:00Z");
        when(failurePatternMapper.listBetweenOrderByCountDesc(from, to, 100)).thenReturn(List.of());

        assertThat(service.getFailurePatterns(from, to)).isEmpty();

        verify(failurePatternMapper).listBetweenOrderByCountDesc(from, to, 100);
    }

    @Test
    void getTokenTrendsBetweenMapsRowsToDtoPoints() {
        Instant from = Instant.parse("2026-07-01T00:00:00Z");
        Instant to = Instant.parse("2026-07-09T00:00:00Z");
        TraceTokenTrendRow row = new TraceTokenTrendRow();
        row.setDay(LocalDate.parse("2026-07-02"));
        row.setInputTokens(300L);
        row.setOutputTokens(120L);
        when(traceMapper.tokenTrendBetween(from, to)).thenReturn(List.of(row));

        List<TraceStatsDto.TokenTrendPoint> points = service.getTokenTrends(from, to);

        assertThat(points).singleElement()
                .satisfies(point -> {
                    assertThat(point.day()).isEqualTo(LocalDate.parse("2026-07-02"));
                    assertThat(point.inputTokens()).isEqualTo(300L);
                    assertThat(point.outputTokens()).isEqualTo(120L);
                });
    }

    @Test
    void getTraceThrowsWhenMissing() {
        when(traceMapper.findByCorrelationId("missing")).thenReturn(null);
        assertThatThrownBy(() -> service.getTrace("missing"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void getTraceProjectsNativeV4AsAnAdminExecutionArchive() {
        String hash = "a".repeat(64);
        JsonNode detail = traceJson(
                "native-v4",
                "[{\"observation\":\"raw column secret\"}]",
                """
                        {
                          "components":{
                            "traceSchema":"agent-trace-v4",
                            "prompt":"agent-prompt-v1",
                            "skillSelector":"skill-selector-v1",
                            "model":"deepseek-chat",
                            "retrieval":"document-rrf-v1",
                            "citationValidator":"evidence-citation-gate-v1",
                            "tools":"agent-tool-v1",
                            "unknownComponent":"raw component secret"
                          },
                          "skill":{
                            "selectionStatus":"SELECTED",
                            "id":"page-explain",
                            "version":"v1",
                            "validationStatus":"VALID"
                          },
                          "retrieval":{
                            "strategy":"document-rrf-v1",
                            "statuses":{"semantic":"SUCCESS_RESULTS","keyword":"SUCCESS_EMPTY","entity":"TIMEOUT","unknown":"raw route secret"},
                            "evidenceCount":1,
                            "evidenceSnapshotHash":"evidence-hash",
                            "degraded":true,
                            "citationValidationStatus":"VALID",
                            "evidence":[{"citationId":"C1","documentId":"post:1","source":"semantic","sourceVersion":"v1","sourceHash":"source-hash","excerpt":"raw excerpt secret"}]
                          },
                          "turn":{
                            "requestId":"request-1",
                            "turnId":"turn-1",
                            "chatSessionId":"private-session",
                            "connectionId":"private-connection",
                            "budgetMs":30000,
                            "maxSteps":4,
                            "timeoutStage":"",
                            "cancelled":false,
                            "clientDeliveryStatus":"DELIVERED",
                            "clientTerminalType":"final",
                            "clientDeliveryCode":""
                          },
                          "memory":{"writeStatus":"COMMITTED","failureCode":""},
                          "toolPlan":{"reason":"evidence_required","effectiveTools":["fulltext_search"]},
                          "answerTiming":{"modelFirstTokenMs":12,"safeAnswerReadyMs":30,"firstClientDeltaMs":35},
                          "capture":{
                            "level":"ADMIN_FULL","policyVersion":"trace-admin-full-v1",
                            "maxPerFieldChars":131072,"maxCapturedChars":2097152,
                            "capturedChars":418,"truncated":false,"truncatedFields":0,"redactions":1
                          },
                          "privacy":{"eventLimit":256,"droppedEvents":2,"truncatedToolOutputs":1},
                          "input":{
                            "fingerprint":"input-hash","questionFingerprint":"question-hash","pageContextFingerprint":"page-hash"
                          },
                          "runMode":"candidate",
                          "failureType":"NONE",
                          "outcomeReason":"NONE",
                          "llmCallCount":1,
                          "steps":[{"stepIndex":0,"action":"fulltext_search","llmMs":20,"toolMs":15}],
                          "toolCalls":[{"observation":"raw payload tool secret"}],
                          "events":[
                            {
                              "sequence":1,"phase":"accepted","type":"lifecycle","name":"turn_context","status":"ACCEPTED",
                              "started_offset_ms":0,"duration_ms":0,
                              "details":{
                                "model":"deepseek-chat","run_mode":"candidate",
                                "question":{"text":"查询《迷宫饭》的评分和角色","sourceChars":14,"sha256":"%s","truncated":false,"credentialRedacted":false},
                                "pageContext":{"text":"正在陪读《吃掉红龙这件事》","sourceChars":15,"sha256":"%s","truncated":false,"credentialRedacted":false}
                              }
                            },
                            {
                              "sequence":2,"step_index":0,"phase":"llm","type":"llm","name":"llm_call","status":"SUCCESS",
                              "started_offset_ms":5,"duration_ms":20,"attempt":1,"budget_before_ms":30000,"budget_after_ms":29980,
                              "details":{
                                "purpose":"LOOP_DECISION","model":"deepseek-chat","input_chars":120,"output_chars":32,"first_token_ms":7,
                                "systemPrompt":{"text":"你是珂朵莉，必须根据证据回答。","sourceChars":16,"sha256":"%s","truncated":false,"credentialRedacted":false},
                                "userPrompt":{"text":"问题：查询《迷宫饭》的评分和角色","sourceChars":18,"sha256":"%s","truncated":false,"credentialRedacted":false},
                                "rawOutput":{"text":"{\\"action\\":\\"bangumi_search\\"}","sourceChars":27,"sha256":"%s","truncated":false,"credentialRedacted":false},
                                "answer":"unknown field is not projected"
                              }
                            },
                            {
                              "sequence":3,"step_index":0,"phase":"tool","type":"tool","name":"fulltext_search","status":"SUCCESS",
                              "started_offset_ms":25,"duration_ms":15,"attempt":1,"budget_before_ms":29980,"budget_after_ms":29965,
                              "details":{
                                "operation":"fulltext_search","provider":"mysql","sourcePolicy":"public_only",
                                "sanitizedInput":{
                                  "query":"safe query",
                                  "url":"https://alice:uri-secret@example.com/article?topic=keep&token=query-secret",
                                  "_userQuestion":"private question secret",
                                  "password":"private password secret"
                                },
                                "outputPreview":"safe preview","outputSha256":"%s","outputChars":12,"outputTruncated":false,
                                "resultCount":1,"selectedIds":["post:1"],
                                "attributes":{
                                  "requestedUrl":"https://example.com/article",
                                  "tokenBudget":2048,
                                  "httpStatus":200,
                                  "redirectChain":[{"status":302,"url":"https://example.com/final"}],
                                  "authorization":"[REDACTED]"
                                },
                                "input":{"text":"{\\"keyword\\":\\"迷宫饭\\",\\"_userQuestion\\":\\"查询《迷宫饭》的评分和角色\\",\\"password\\":\\"[REDACTED]\\"}","sourceChars":74,"sha256":"%s","truncated":false,"credentialRedacted":true},
                                "observation":{"text":"Bangumi 返回作品、评分、放送日期与角色列表。","sourceChars":26,"sha256":"%s","truncated":false,"credentialRedacted":false}
                              }
                            },
                            {
                              "sequence":4,"phase":"delivery","type":"lifecycle","name":"terminal","status":"SUCCESS",
                              "started_offset_ms":40,"duration_ms":0,
                              "details":{
                                "answer_chars":31,
                                "answer":{"text":"《迷宫饭》是一部很认真讨论吃饭的作品。[E1]","sourceChars":31,"sha256":"%s","truncated":false,"credentialRedacted":false}
                              }
                            }
                          ],
                          "rawRoot":"raw root secret"
                        }
                        """.formatted(hash, hash, hash, hash, hash, hash, hash, hash, hash));

        assertThat(detail.path("correlationId").asText()).isEqualTo("native-v4");
        assertThat(detail.path("compatibility").asText()).isEqualTo("NATIVE_V4");
        assertThat(detail.path("timingAccuracy").asText()).isEqualTo("EXACT");
        assertThat(detail.has("tracePayload")).isFalse();
        assertThat(detail.has("toolCalls")).isFalse();
        assertThat(detail.has("steps")).isFalse();
        assertThat(detail.has("unassignedEvents")).isFalse();

        JsonNode llmEvent = detail.path("phases").path(1).path("events").path(0);
        assertThat(llmEvent.path("id").asText()).isEqualTo("native-v4:2");
        assertThat(llmEvent.path("sequence").asInt()).isEqualTo(2);
        assertThat(llmEvent.path("stepIndex").asInt()).isZero();
        assertThat(llmEvent.path("startedOffsetMs").asLong()).isEqualTo(5L);
        assertThat(llmEvent.path("durationMs").asLong()).isEqualTo(20L);
        assertThat(llmEvent.path("details").path("purpose").asText()).isEqualTo("LOOP_DECISION");
        assertThat(llmEvent.path("details").path("firstTokenMs").asLong()).isEqualTo(7L);
        assertThat(llmEvent.path("details").path("systemPrompt").path("text").asText())
                .isEqualTo("你是珂朵莉，必须根据证据回答。");
        assertThat(llmEvent.path("details").path("userPrompt").path("text").asText())
                .contains("查询《迷宫饭》的评分和角色");
        assertThat(llmEvent.path("details").path("rawOutput").path("text").asText())
                .contains("bangumi_search");
        assertThat(llmEvent.path("details").has("answer")).isFalse();

        JsonNode toolDetails = detail.path("phases").path(2).path("events").path(0).path("details");
        assertThat(toolDetails.path("sanitizedInput").path("query").asText()).isEqualTo("safe query");
        assertThat(toolDetails.path("sanitizedInput").path("url").asText())
                .isEqualTo("https://[REDACTED]@example.com/article?topic=keep&token=[REDACTED]");
        assertThat(toolDetails.path("sanitizedInput").has("_userQuestion")).isFalse();
        assertThat(toolDetails.path("sanitizedInput").has("password")).isFalse();
        assertThat(toolDetails.path("outputSha256").asText()).isEqualTo(hash);
        assertThat(toolDetails.path("selectedIds").path(0).asText()).isEqualTo("post:1");
        assertThat(toolDetails.path("rawInput").path("text").asText())
                .contains("_userQuestion", "查询《迷宫饭》的评分和角色", "[REDACTED]")
                .doesNotContain("private password secret");
        assertThat(toolDetails.path("rawInput").path("credentialRedacted").asBoolean()).isTrue();
        assertThat(toolDetails.path("rawObservation").path("text").asText())
                .contains("作品、评分、放送日期与角色列表");
        assertThat(toolDetails.path("attributes").path("requestedUrl").asText())
                .isEqualTo("https://example.com/article");
        assertThat(toolDetails.path("attributes").path("tokenBudget").asInt())
                .isEqualTo(2048);
        assertThat(toolDetails.path("attributes").path("redirectChain").path(0).path("status").asInt())
                .isEqualTo(302);
        assertThat(toolDetails.path("attributes").path("authorization").asText())
                .isEqualTo("[REDACTED]");

        JsonNode terminalDetails = detail.path("phases").path(3).path("events").path(0).path("details");
        assertThat(terminalDetails.path("finalAnswer").path("text").asText())
                .contains("很认真讨论吃饭", "[E1]");

        JsonNode metadata = detail.path("metadata");
        assertThat(metadata.path("components").path("traceSchema").asText()).isEqualTo("agent-trace-v4");
        assertThat(metadata.path("retrieval").path("statuses").path("semantic").asText())
                .isEqualTo("SUCCESS_RESULTS");
        assertThat(metadata.path("turn").path("chatSessionId").asText()).isEqualTo("private-session");
        assertThat(metadata.path("turn").path("connectionId").asText()).isEqualTo("private-connection");
        assertThat(metadata.path("turn").path("maxSteps").asInt()).isEqualTo(4);
        assertThat(metadata.path("toolPlan").path("effectiveTools").path(0).asText())
                .isEqualTo("fulltext_search");
        assertThat(metadata.path("steps").path(0).path("stepIndex").asInt()).isZero();
        assertThat(metadata.path("steps").path(0).path("action").asText())
                .isEqualTo("fulltext_search");
        assertThat(metadata.path("steps").path(0).path("llmMs").asLong()).isEqualTo(20L);
        assertThat(metadata.path("steps").path(0).path("toolMs").asLong()).isEqualTo(15L);
        assertThat(metadata.path("capture").path("level").asText()).isEqualTo("ADMIN_FULL");
        assertThat(metadata.path("capture").path("credentialRedactions").asInt()).isEqualTo(1);
        assertThat(metadata.path("completeness").path("eventLimit").asInt()).isEqualTo(256);
        assertThat(metadata.path("completeness").path("droppedEvents").asInt()).isEqualTo(2);
        assertThat(metadata.path("completeness").path("truncatedToolOutputs").asInt()).isEqualTo(1);
        assertThat(metadata.path("completeness").path("complete").asBoolean()).isFalse();
        assertThat(metadata.path("input").path("question").path("text").asText())
                .isEqualTo("查询《迷宫饭》的评分和角色");
        assertThat(metadata.path("input").path("pageContext").path("text").asText())
                .isEqualTo("正在陪读《吃掉红龙这件事》");
        assertThat(detail.toString()).doesNotContain(
                "raw column secret",
                "raw component secret",
                "raw route secret",
                "raw excerpt secret",
                "raw payload tool secret",
                "private password secret",
                "uri-secret",
                "query-secret",
                "unknown field is not projected",
                "raw root secret");
    }

    @Test
    void getTraceProjectsLegacyV3CallsWithDurationOnlyTimingAndStrictSummaries() {
        String inputSummary = "sha256=" + "b".repeat(64) + ";chars=12";
        String observationSummary = "sha256=" + "c".repeat(64) + ";chars=34";
        JsonNode detail = traceJson(
                "legacy-v3",
                "[]",
                """
                {
                  "components":{"traceSchema":"agent-trace-v3","model":"legacy-model"},
                  "llmCalls":[{"sequence":10,"step_index":0,"purpose":"LOOP_DECISION","model":"legacy-model","status":"SUCCESS","duration_ms":8,"input_chars":20,"output_chars":10,"first_token_ms":3}],
                  "toolCalls":[{"sequence":11,"step_index":0,"tool":"search","duration_ms":12,"success":true,"input_summary":"%s","observation_summary":"%s"}]
                }
                """.formatted(inputSummary, observationSummary));

        assertThat(detail.path("compatibility").asText()).isEqualTo("LEGACY_V3");
        assertThat(detail.path("timingAccuracy").asText()).isEqualTo("DURATION_ONLY");
        JsonNode llmEvent = detail.path("phases").path(0).path("events").path(0);
        assertThat(llmEvent.path("sequence").asInt()).isEqualTo(10);
        assertThat(llmEvent.path("startedOffsetMs").isNull()).isTrue();
        assertThat(llmEvent.path("durationMs").asLong()).isEqualTo(8L);
        JsonNode toolEvent = detail.path("phases").path(1).path("events").path(0);
        assertThat(toolEvent.path("sequence").asInt()).isEqualTo(11);
        assertThat(toolEvent.path("startedOffsetMs").isNull()).isTrue();
        assertThat(toolEvent.path("details").path("inputSummary").asText()).isEqualTo(inputSummary);
        assertThat(toolEvent.path("details").path("observationSummary").asText())
                .isEqualTo(observationSummary);
        assertThat(toolEvent.path("details").path("selectedIds").isArray()).isTrue();
        assertThat(toolEvent.path("details").path("selectedIds")).isEmpty();
    }

    @Test
    void getTracePreservesLegacyV3GlobalSequenceAcrossLlmAndToolCalls() {
        JsonNode detail = traceJson(
                "legacy-v3-interleaved",
                "[]",
                """
                        {
                          "components":{"traceSchema":"agent-trace-v3"},
                          "llmCalls":[
                            {"sequence":1,"step_index":0,"duration_ms":8},
                            {"sequence":3,"step_index":1,"duration_ms":6}
                          ],
                          "toolCalls":[
                            {"sequence":2,"step_index":0,"tool":"search","duration_ms":12,"success":true}
                          ]
                        }
                        """);

        assertThat(detail.path("phases")).hasSize(3);
        assertThat(detail.path("phases").path(0).path("phase").asText()).isEqualTo("llm");
        assertThat(detail.path("phases").path(0).path("events").path(0).path("sequence").asInt())
                .isEqualTo(1);
        assertThat(detail.path("phases").path(1).path("phase").asText()).isEqualTo("tool");
        assertThat(detail.path("phases").path(1).path("events").path(0).path("sequence").asInt())
                .isEqualTo(2);
        assertThat(detail.path("phases").path(2).path("phase").asText()).isEqualTo("llm");
        assertThat(detail.path("phases").path(2).path("events").path(0).path("sequence").asInt())
                .isEqualTo(3);
    }

    @Test
    void getTraceProjectsLegacyToolColumnWithoutExposingTheColumn() {
        String summary = "sha256=" + "d".repeat(64) + ";chars=5";
        JsonNode detail = traceJson(
                "legacy-column",
                "[{\"tool\":\"search\",\"duration_ms\":5,\"success\":true,\"input_summary\":\""
                        + summary + "\",\"observation_summary\":\"" + summary + "\"}]",
                """
                        {
                          "components":{"traceSchema":"agent-trace-v3"},
                          "llmCalls":[]
                        }
                        """);

        assertThat(detail.path("compatibility").asText()).isEqualTo("LEGACY_V3");
        assertThat(detail.path("phases").path(0).path("phase").asText()).isEqualTo("tool");
        assertThat(detail.path("phases").path(0).path("events").path(0)
                .path("details").path("inputSummary").asText()).isEqualTo(summary);
        assertThat(detail.path("metadata").path("toolCallCount").asInt()).isEqualTo(1);
        assertThat(detail.has("toolCalls")).isFalse();
    }

    @Test
    void getTraceUsesUnknownForLegacyCallsWithoutAnExplicitOutcome() {
        JsonNode detail = traceJson(
                "legacy-unknown",
                "[]",
                """
                        {
                          "components":{"traceSchema":"agent-trace-v3"},
                          "llmCalls":[{"duration_ms":8}],
                          "toolCalls":[{"tool":"search","duration_ms":12}]
                        }
                        """);

        assertThat(detail.path("phases").path(0).path("events").path(0).path("status").asText())
                .isEqualTo("UNKNOWN");
        assertThat(detail.path("phases").path(1).path("events").path(0).path("status").asText())
                .isEqualTo("UNKNOWN");
    }

    @Test
    void getTraceKeepsCompatibleMetadataCollectionsStableWhenTheyAreEmpty() {
        JsonNode detail = traceJson(
                "empty-collections",
                "[]",
                """
                        {
                          "components":{"traceSchema":"agent-trace-v4"},
                          "retrieval":{},
                          "toolPlan":{},
                          "events":[]
                        }
                        """);

        assertThat(detail.path("metadata").path("retrieval").path("statuses").isObject()).isTrue();
        assertThat(detail.path("metadata").path("retrieval").path("statuses")).isEmpty();
        assertThat(detail.path("metadata").path("retrieval").path("evidence").isArray()).isTrue();
        assertThat(detail.path("metadata").path("retrieval").path("evidence")).isEmpty();
        assertThat(detail.path("metadata").path("toolPlan").path("effectiveTools").isArray()).isTrue();
        assertThat(detail.path("metadata").path("toolPlan").path("effectiveTools")).isEmpty();
    }

    @Test
    void getTraceKeepsOlderNativeTurnMetadataCompatibleWithoutMaxSteps() {
        JsonNode detail = traceJson(
                "native-without-max-steps",
                "[]",
                """
                        {
                          "components":{"traceSchema":"agent-trace-v4"},
                          "turn":{"budgetMs":30000},
                          "events":[]
                        }
                        """);

        assertThat(detail.path("compatibility").asText()).isEqualTo("NATIVE_V4");
        assertThat(detail.path("metadata").path("turn").path("budgetMs").asLong())
                .isEqualTo(30_000L);
        assertThat(detail.path("metadata").path("turn").has("maxSteps")).isFalse();
    }

    @Test
    void getTracePreservesCurrentPostRetrievalStatusWithoutEvidence() {
        JsonNode detail = traceJson(
                "current-post-empty",
                "[]",
                """
                        {
                          "components":{"traceSchema":"agent-trace-v4"},
                          "retrieval":{
                            "strategy":"current-post-rag-v1",
                            "statuses":{"current_post":"SUCCESS_EMPTY"},
                            "evidenceCount":0,
                            "evidence":[]
                          },
                          "events":[]
                        }
                        """);

        JsonNode retrieval = detail.path("metadata").path("retrieval");
        assertThat(retrieval.path("statuses").path("current_post").asText())
                .isEqualTo("SUCCESS_EMPTY");
        assertThat(retrieval.path("evidence")).isEmpty();
    }

    @Test
    void getTraceBoundsToolDiagnosticMapsAndLists() {
        var root = objectMapper.createObjectNode();
        root.putObject("components").put("traceSchema", "agent-trace-v4");
        var event = root.putArray("events").addObject();
        event.put("sequence", 1);
        event.put("phase", "tool");
        event.put("type", "tool");
        event.put("name", "search");
        event.put("status", "SUCCESS");
        event.put("started_offset_ms", 0);
        event.put("duration_ms", 1);
        var details = event.putObject("details");
        details.put("operation", "search");
        var input = details.putObject("sanitizedInput");
        var selectedIds = details.putArray("selectedIds");
        for (int index = 0; index < 25; index++) {
            input.put("field" + index, "value" + index);
            selectedIds.add("post:" + index);
        }

        JsonNode detail = traceJson("bounded-details", "[]", root.toString());
        JsonNode projected = detail.path("phases").path(0).path("events").path(0).path("details");

        assertThat(projected.path("sanitizedInput")).hasSize(20);
        assertThat(projected.path("selectedIds")).hasSize(20);
    }

    @Test
    void getTraceOmitsLegacyRawInputAndObservationWhenTheyAreNotStrictSummaries() {
        JsonNode detail = traceJson(
                "legacy-unsafe",
                "[]",
                """
                {
                  "components":{"traceSchema":"agent-trace-v3"},
                  "llmCalls":[],
                  "toolCalls":[{
                    "tool":"search","duration_ms":12,"success":false,
                    "input_summary":"private query secret",
                    "observation_summary":"private observation secret",
                    "input":"private legacy input secret",
                    "observation":"private legacy output secret"
                  }]
                }
                """);

        JsonNode details = detail.path("phases").path(0).path("events").path(0).path("details");
        assertThat(details.has("inputSummary")).isFalse();
        assertThat(details.has("observationSummary")).isFalse();
        assertThat(detail.toString()).doesNotContain(
                "private query secret",
                "private observation secret",
                "private legacy input secret",
                "private legacy output secret");
    }

    @Test
    void getTraceFailsClosedForUnsupportedSchema() {
        JsonNode detail = traceJson(
                "unsupported",
                "[{\"input\":\"raw column secret\"}]",
                """
                        {
                          "components":{"traceSchema":"agent-trace-v2"},
                          "events":[{"details":{"prompt":"raw unsupported secret"}}]
                        }
                        """);

        assertThat(detail.path("compatibility").asText()).isEqualTo("UNSUPPORTED");
        assertThat(detail.path("timingAccuracy").asText()).isEqualTo("NONE");
        assertThat(detail.path("phases")).isEmpty();
        assertThat(detail.path("metadata").isNull()).isTrue();
        assertThat(detail.toString()).doesNotContain("raw column secret", "raw unsupported secret");
    }

    @Test
    void getTraceFailsClosedForMalformedJson() {
        JsonNode detail = traceJson(
                "malformed-json",
                "[{\"observation\":\"raw column secret\"}]",
                "{\"components\":{\"traceSchema\":\"agent-trace-v4\"},\"events\":[");

        assertThat(detail.path("compatibility").asText()).isEqualTo("MALFORMED");
        assertThat(detail.path("timingAccuracy").asText()).isEqualTo("NONE");
        assertThat(detail.path("phases")).isEmpty();
        assertThat(detail.path("metadata").isNull()).isTrue();
        assertThat(detail.toString()).doesNotContain("raw column secret");
    }

    @Test
    void getTraceFailsClosedWhenNativeV4EventsHaveTheWrongShape() {
        JsonNode detail = traceJson(
                "malformed-v4",
                "[]",
                """
                        {
                          "components":{"traceSchema":"agent-trace-v4"},
                          "events":{"sequence":1,"details":{"prompt":"raw malformed secret"}}
                        }
                        """);

        assertThat(detail.path("compatibility").asText()).isEqualTo("MALFORMED");
        assertThat(detail.path("phases")).isEmpty();
        assertThat(detail.toString()).doesNotContain("raw malformed secret");
    }

    @Test
    void listTracesPassesExactCorrelationAndDateFiltersToMapper() {
        Instant from = Instant.parse("2026-07-01T00:00:00Z");
        Instant to = Instant.parse("2026-07-10T00:00:00Z");
        when(traceMapper.list(
                TraceStatus.FAILURE.name(), 42L, from, to, "corr-exact", 25, 50))
                .thenReturn(List.of());
        when(traceMapper.count(
                TraceStatus.FAILURE.name(), 42L, from, to, "corr-exact"))
                .thenReturn(0L);

        var response = service.listTraces(
                2,
                25,
                TraceStatus.FAILURE.name(),
                42L,
                from,
                to,
                "corr-exact");

        assertThat(response.items()).isEmpty();
        verify(traceMapper).list(
                TraceStatus.FAILURE.name(), 42L, from, to, "corr-exact", 25, 50);
        verify(traceMapper).count(
                TraceStatus.FAILURE.name(), 42L, from, to, "corr-exact");
    }

    private JsonNode traceJson(String correlationId, String toolCalls, String tracePayload) {
        ExecutionTraceRow row = new ExecutionTraceRow();
        row.setCorrelationId(correlationId);
        row.setStatus(TraceStatus.SUCCESS.name());
        row.setToolCalls(toolCalls);
        row.setTracePayload(tracePayload);
        when(traceMapper.findByCorrelationId(correlationId)).thenReturn(row);
        return objectMapper.valueToTree(service.getTrace(correlationId));
    }
}
