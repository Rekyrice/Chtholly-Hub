package com.chtholly.agent.eval;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs the Chtholly Agent evaluation suite and aggregates judge scores.
 */
public class EvaluationRunner {

    private static final Set<String> COMPUTED_DIMENSIONS = Set.of("style_consistency", "keyword_grounding");
    private static final int CONSISTENCY_REPEAT_COUNT = 3;

    private final EvaluationQuestionSet questionSet;
    private final EvaluationDimensionSet dimensionSet;
    private final AgentResponder responder;
    private final JudgeClient judgeClient;
    private final ConsistencyJudgeClient consistencyJudge;
    private final EvalResultStore resultStore;

    public EvaluationRunner(EvaluationQuestionSet questionSet,
                            EvaluationDimensionSet dimensionSet,
                            AgentResponder responder,
                            JudgeClient judgeClient,
                            EvalResultStore resultStore) {
        this(questionSet, dimensionSet, responder, judgeClient, null, resultStore);
    }

    public EvaluationRunner(EvaluationQuestionSet questionSet,
                            EvaluationDimensionSet dimensionSet,
                            AgentResponder responder,
                            JudgeClient judgeClient,
                            ConsistencyJudgeClient consistencyJudge,
                            EvalResultStore resultStore) {
        this.questionSet = questionSet;
        this.dimensionSet = dimensionSet;
        this.responder = responder;
        this.judgeClient = judgeClient;
        this.consistencyJudge = consistencyJudge;
        this.resultStore = resultStore;
    }

    /**
     * Runs an evaluation and writes a JSON report.
     *
     * @param options run options
     * @return stored report including optional comparison data
     */
    public EvaluationReport run(EvaluationRunOptions options) {
        List<EvaluationQuestion> selected = selectedQuestions(options);
        List<QuestionEvaluationResult> results = new ArrayList<>(selected.size());
        for (EvaluationQuestion question : selected) {
            results.add(evaluateOne(question, options));
        }
        EvaluationReport report = aggregate(options, results);
        EvaluationReport stored = resultStore.write(report);
        printSummary(stored);
        return stored;
    }

    /**
     * 对同一问题重复调用 Agent，并用 LLM 判断风格是否一致。
     */
    public ConsistencyEvaluationResult evaluateConsistency(EvaluationQuestion question, EvaluationRunOptions options) {
        List<String> responses = new ArrayList<>(CONSISTENCY_REPEAT_COUNT);
        for (int i = 0; i < CONSISTENCY_REPEAT_COUNT; i++) {
            responses.add(answerSafely(question, options));
        }
        EvaluationScore score = judgeConsistency(responses);
        return new ConsistencyEvaluationResult(responses, score);
    }

    private List<EvaluationQuestion> selectedQuestions(EvaluationRunOptions options) {
        if (!options.quickMode()) {
            return questionSet.questions();
        }
        return questionSet.questions().stream()
                .limit(Math.max(1, options.quickLimit()))
                .toList();
    }

    private QuestionEvaluationResult evaluateOne(EvaluationQuestion question, EvaluationRunOptions options) {
        String response;
        List<String> consistencyResponses = List.of();

        if (question.consistencyCheck()) {
            ConsistencyEvaluationResult consistency = evaluateConsistency(question, options);
            consistencyResponses = consistency.responses();
            response = consistencyResponses.isEmpty() ? "" : consistencyResponses.getFirst();
        } else {
            response = answerSafely(question, options);
        }

        EvaluationScores scores;
        try {
            scores = judgeClient.judge(new JudgeRequest(question, response, judgeDimensions(question)));
        } catch (Exception e) {
            scores = EvaluationScores.uniform(judgeDimensionKeys(question), 1, "Judge failed: " + e.getMessage());
        }

        scores = applyKeywordGrounding(question, response, scores);

        if (question.consistencyCheck()) {
            scores = scores.withDimension("style_consistency", judgeConsistency(consistencyResponses));
        }

        return new QuestionEvaluationResult(
                question.id(), question.category(), question.text(), response, scores, consistencyResponses);
    }

    private String answerSafely(EvaluationQuestion question, EvaluationRunOptions options) {
        try {
            return responder.answer(new EvaluationAgentRequest(question, options.userId()));
        } catch (Exception e) {
            return "[agent_error] " + e.getMessage();
        }
    }

    private EvaluationScores applyKeywordGrounding(
            EvaluationQuestion question, String response, EvaluationScores scores) {
        EvaluationScore keywordScore = KeywordGroundingScorer.score(response, question.expectedKeywords());
        if (keywordScore == null) {
            return scores;
        }
        return scores.withDimension("keyword_grounding", keywordScore);
    }

    private EvaluationScore judgeConsistency(List<String> responses) {
        if (consistencyJudge == null) {
            return new EvaluationScore(1, "未配置 ConsistencyJudgeClient");
        }
        try {
            return consistencyJudge.judge(responses);
        } catch (Exception e) {
            return new EvaluationScore(1, "一致性 Judge 失败: " + e.getMessage());
        }
    }

    private List<EvaluationDimension> judgeDimensions(EvaluationQuestion question) {
        return dimensionSet.dimensions().stream()
                .filter(dimension -> !COMPUTED_DIMENSIONS.contains(dimension.key()))
                .filter(dimension -> includeReasoningDepth(dimension.key(), question.category()))
                .toList();
    }

    private List<String> judgeDimensionKeys(EvaluationQuestion question) {
        return judgeDimensions(question).stream().map(EvaluationDimension::key).toList();
    }

    private static boolean includeReasoningDepth(String key, String category) {
        if (!"reasoning_depth".equals(key)) {
            return true;
        }
        return "complex_reasoning".equals(category);
    }

    private EvaluationReport aggregate(EvaluationRunOptions options, List<QuestionEvaluationResult> results) {
        Map<String, Double> averages = new LinkedHashMap<>();
        for (String key : dimensionSet.keys()) {
            double average = results.stream()
                    .filter(result -> result.scores().hasDimension(key))
                    .mapToInt(result -> result.scores().score(key))
                    .average()
                    .orElse(0.0);
            averages.put(key, EvaluationReport.round(average));
        }

        double overall = results.stream()
                .mapToDouble(result -> result.scores().overall())
                .average()
                .orElse(0.0);
        List<QuestionEvaluationResult> worst = results.stream()
                .sorted(Comparator.comparingDouble(result -> result.scores().overall()))
                .limit(5)
                .toList();

        return new EvaluationReport(
                "eval-" + DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(":", "-"),
                options.quickMode(),
                results.size(),
                averages,
                EvaluationReport.round(overall),
                null,
                null,
                worst,
                results);
    }

    private void printSummary(EvaluationReport report) {
        System.out.printf("Eval %s: overall=%.2f, questions=%d%n",
                report.runId(), report.overallScore(), report.totalQuestions());
        for (Map.Entry<String, Double> entry : report.dimensionAverages().entrySet()) {
            System.out.printf("  %s=%.2f%n", entry.getKey(), entry.getValue());
        }
    }
}
