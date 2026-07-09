package com.chtholly.agent.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationRunnerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void given_evalYamlResources_when_load_then_containsFiftyQuestionsAndNineDimensions() throws Exception {
        EvaluationQuestionSet questions = EvaluationQuestionSet.loadClasspath("eval/test-questions.yml");
        EvaluationDimensionSet dimensions = EvaluationDimensionSet.loadClasspath("eval/dimensions.yml");

        assertThat(questions.questions()).hasSize(50);
        assertThat(questions.questions()).extracting(EvaluationQuestion::category)
                .contains(
                        "casual_emotion",
                        "anime_knowledge",
                        "site_content",
                        "memory_recall",
                        "complex_reasoning");
        assertThat(dimensions.dimensions()).hasSize(9);
        assertThat(dimensions.keys()).containsExactly(
                "response_quality",
                "persona_consistency",
                "memory_recall",
                "knowledge_accuracy",
                "emotional_expression",
                "proactivity",
                "style_consistency",
                "keyword_grounding",
                "reasoning_depth");
    }

    @Test
    void given_quickMode_when_run_then_evaluatesTenQuestionsAndWritesReport(@TempDir Path tempDir) throws Exception {
        EvaluationRunner runner = new EvaluationRunner(
                EvaluationQuestionSet.loadClasspath("eval/test-questions.yml"),
                EvaluationDimensionSet.loadClasspath("eval/dimensions.yml"),
                request -> "嗯，我会认真听你说的。",
                request -> EvaluationScores.uniform(request.dimensionKeys(), 4, "稳定的基线回复。"),
                new ConsistencyJudgeClient(prompt -> "style_consistency: 4 — 稳定的基线风格。"),
                new EvalResultStore(tempDir, objectMapper));

        EvaluationReport report = runner.run(EvaluationRunOptions.quick());

        assertThat(report.totalQuestions()).isEqualTo(10);
        assertThat(report.dimensionAverages()).containsEntry("response_quality", 4.0);
        assertThat(report.overallScore()).isEqualTo(4.0);
        assertThat(report.worstQuestions()).hasSize(5);
        assertThat(Files.list(tempDir).filter(path -> path.toString().endsWith(".json")).count()).isEqualTo(1);
    }

    @Test
    void given_previousReport_when_storeCurrent_then_reportContainsScoreDelta(@TempDir Path tempDir) throws Exception {
        EvalResultStore store = new EvalResultStore(tempDir, objectMapper);
        store.write(new EvaluationReport(
                "old-run",
                false,
                1,
                Map.of("response_quality", 3.0),
                3.0,
                null,
                null,
                List.of(),
                List.of()));

        EvaluationReport current = store.write(new EvaluationReport(
                "new-run",
                false,
                1,
                Map.of("response_quality", 4.0),
                4.0,
                null,
                null,
                List.of(),
                List.of()));

        assertThat(current.previousOverallScore()).isEqualTo(3.0);
        assertThat(current.overallDelta()).isEqualTo(1.0);
    }

    @Test
    void given_llmJudgeOutput_when_parse_then_extractsScoresAndReasons() {
        String output = """
                response_quality: 5 — 自然，而且愿意继续聊。
                persona_consistency: 4 — 基本符合珂朵莉。
                memory_recall: 3 — 没有明显编造。
                knowledge_accuracy: 4 — 信息较准确。
                emotional_expression: 5 — 安慰很克制。
                proactivity: 4 — 有适度引导。
                overall: 4.2
                """;

        EvaluationScores scores = LlmJudgeClient.parseScores(output);

        assertThat(scores.score("response_quality")).isEqualTo(5);
        assertThat(scores.reason("emotional_expression")).contains("克制");
        assertThat(scores.overall()).isEqualTo(4.2);
    }

    @Test
    void given_expectedKeywords_when_score_then_countsHits() {
        EvaluationScore score = KeywordGroundingScorer.score(
                "芙莉莲是精灵，旅行了很久。",
                List.of("芙莉莲", "精灵", "魔法"));

        assertThat(score.score()).isEqualTo(3);
        assertThat(score.reason()).contains("2/3");
    }

    @Test
    void given_consistencyResponses_when_judge_then_parsesStyleScore() {
        String output = "style_consistency: 4 — 三次回复语气相近。";

        EvaluationScore score = ConsistencyJudgeClient.parseScore(output);

        assertThat(score.score()).isEqualTo(4);
        assertThat(score.reason()).contains("语气");
    }

    @Test
    void given_consistencyQuestion_when_evaluateConsistency_then_collectsThreeResponses() {
        EvaluationQuestion question = new EvaluationQuestion(
                "casual_01",
                "casual_emotion",
                "今天好累啊",
                "",
                "",
                List.of(),
                true);
        EvaluationRunner runner = new EvaluationRunner(
                EvaluationQuestionSet.loadClasspath("eval/test-questions.yml"),
                EvaluationDimensionSet.loadClasspath("eval/dimensions.yml"),
                request -> "我会陪着你的。",
                request -> EvaluationScores.uniform(request.dimensionKeys(), 4, "ok"),
                new ConsistencyJudgeClient(prompt -> "style_consistency: 5 — 一致"),
                new EvalResultStore(Path.of("target/eval-test"), objectMapper));

        ConsistencyEvaluationResult result = runner.evaluateConsistency(question, EvaluationRunOptions.quick());

        assertThat(result.responses()).hasSize(3);
        assertThat(result.score().score()).isEqualTo(5);
    }

    @Test
    void given_reportJson_when_written_then_containsPerQuestionScores(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeFalse(Boolean.getBoolean("quick"));
        EvaluationRunner runner = new EvaluationRunner(
                EvaluationQuestionSet.loadClasspath("eval/test-questions.yml"),
                EvaluationDimensionSet.loadClasspath("eval/dimensions.yml"),
                request -> "我记得，你之前说想写关于时间感的文章。",
                request -> EvaluationScores.uniform(request.dimensionKeys(), 5, "命中测试期望。"),
                new EvalResultStore(tempDir, objectMapper));

        EvaluationReport report = runner.run(EvaluationRunOptions.full());
        Path json = tempDir.resolve(report.runId() + ".json");
        JsonNode root = objectMapper.readTree(json.toFile());

        assertThat(report.totalQuestions()).isEqualTo(50);
        assertThat(root.path("results")).hasSize(50);
        assertThat(root.path("dimensionAverages").path("response_quality").asDouble()).isEqualTo(5.0);
        assertThat(root.path("results").get(0)
                .path("scores")
                .path("dimensions")
                .path("response_quality")
                .path("score")
                .asInt())
                .isEqualTo(5);
    }

    @Test
    void runEvaluationFromSystemProperties(@TempDir Path tempDir) throws Exception {
        String mode = System.getProperty("eval.mode");
        Assumptions.assumeTrue(mode != null && !mode.isBlank());

        Path outputDir = Path.of(System.getProperty("eval.output", tempDir.toString()));
        EvaluationRunner runner = new EvaluationRunner(
                EvaluationQuestionSet.loadClasspath("eval/test-questions.yml"),
                EvaluationDimensionSet.loadClasspath("eval/dimensions.yml"),
                request -> "测试回复。",
                request -> EvaluationScores.uniform(request.dimensionKeys(), 4, "CLI 基线。"),
                new ConsistencyJudgeClient(prompt -> "style_consistency: 4 — CLI"),
                new EvalResultStore(outputDir, objectMapper));

        EvaluationReport report = runner.run(EvaluationRunOptions.fromSystemProperties());

        assertThat(report.totalQuestions()).isGreaterThan(0);
        assertThat(report.dimensionAverages()).hasSize(9);
        assertThat(Files.list(outputDir).anyMatch(path -> path.toString().endsWith(".json"))).isTrue();
    }
}
