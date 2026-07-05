package com.chtholly.agent.eval;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the Chtholly Agent evaluation suite and aggregates judge scores.
 */
public class EvaluationRunner {

    private final EvaluationQuestionSet questionSet;
    private final EvaluationDimensionSet dimensionSet;
    private final AgentResponder responder;
    private final JudgeClient judgeClient;
    private final EvalResultStore resultStore;

    public EvaluationRunner(EvaluationQuestionSet questionSet,
                            EvaluationDimensionSet dimensionSet,
                            AgentResponder responder,
                            JudgeClient judgeClient,
                            EvalResultStore resultStore) {
        this.questionSet = questionSet;
        this.dimensionSet = dimensionSet;
        this.responder = responder;
        this.judgeClient = judgeClient;
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
        try {
            response = responder.answer(new EvaluationAgentRequest(question, options.userId()));
        } catch (Exception e) {
            response = "[agent_error] " + e.getMessage();
        }

        EvaluationScores scores;
        try {
            scores = judgeClient.judge(new JudgeRequest(question, response, dimensionSet.dimensions()));
        } catch (Exception e) {
            // Judge failure should surface as a low-quality result, not abort the whole eval run.
            scores = EvaluationScores.uniform(dimensionSet.keys(), 1, "Judge failed: " + e.getMessage());
        }

        return new QuestionEvaluationResult(question.id(), question.category(), question.text(), response, scores);
    }

    private EvaluationReport aggregate(EvaluationRunOptions options, List<QuestionEvaluationResult> results) {
        Map<String, Double> averages = new LinkedHashMap<>();
        for (String key : dimensionSet.keys()) {
            double average = results.stream()
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
