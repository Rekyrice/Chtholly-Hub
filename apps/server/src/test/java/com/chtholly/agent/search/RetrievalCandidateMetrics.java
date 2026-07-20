package com.chtholly.agent.search;

import java.util.List;
import java.util.Set;

/** Computes diagnostics from proposed labels without promoting them to reviewed evidence. */
final class RetrievalCandidateMetrics {

    private static final String CANDIDATE_STATUS = "CANDIDATE_REQUIRES_OWNER_REVIEW";

    private RetrievalCandidateMetrics() {
    }

    static Result evaluate(String labelStatus, List<Observation> observations) {
        if (!CANDIDATE_STATUS.equals(labelStatus)) {
            throw new IllegalArgumentException("Unsupported candidate label status: " + labelStatus);
        }
        List<Observation> safeObservations = List.copyOf(observations);
        long answerableCount = safeObservations.stream().filter(Observation::answerExists).count();
        long noAnswerCount = safeObservations.size() - answerableCount;

        double recallAt5 = safeObservations.stream()
                .filter(Observation::answerExists)
                .mapToDouble(RetrievalCandidateMetrics::recallAt5)
                .average()
                .orElse(0.0);
        double mrr = safeObservations.stream()
                .filter(Observation::answerExists)
                .mapToDouble(RetrievalCandidateMetrics::reciprocalRank)
                .average()
                .orElse(0.0);
        double noAnswerAccuracy = safeObservations.stream()
                .filter(observation -> observation.noAnswer() != observation.answerExists())
                .count() / (double) Math.max(1, safeObservations.size());

        long citationCount = safeObservations.stream()
                .mapToLong(observation -> observation.citations().size())
                .sum();
        Double citationValidityRate = citationCount == 0 ? null : round(
                safeObservations.stream()
                        .flatMap(observation -> observation.citations().stream()
                                .map(citation -> observation.evidenceCitationIds().contains(citation)))
                        .filter(Boolean::booleanValue)
                        .count() / (double) citationCount);

        return new Result(
                round(recallAt5),
                round(mrr),
                citationValidityRate,
                round(noAnswerAccuracy),
                Math.toIntExact(answerableCount),
                Math.toIntExact(noAnswerCount),
                false,
                "CANDIDATE_DIAGNOSTIC_ONLY");
    }

    private static double recallAt5(Observation observation) {
        if (observation.relevantDocumentIds().isEmpty()) {
            return 0.0;
        }
        long hits = observation.rankedDocumentIds().stream()
                .limit(5)
                .filter(observation.relevantDocumentIds()::contains)
                .distinct()
                .count();
        return hits / (double) observation.relevantDocumentIds().size();
    }

    private static double reciprocalRank(Observation observation) {
        for (int index = 0; index < observation.rankedDocumentIds().size(); index++) {
            if (observation.relevantDocumentIds().contains(observation.rankedDocumentIds().get(index))) {
                return 1.0 / (index + 1);
            }
        }
        return 0.0;
    }

    private static double round(double value) {
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }

    record Observation(
            Set<String> relevantDocumentIds,
            boolean answerExists,
            List<String> rankedDocumentIds,
            List<String> citations,
            Set<String> evidenceCitationIds,
            boolean noAnswer) {

        Observation {
            relevantDocumentIds = Set.copyOf(relevantDocumentIds);
            rankedDocumentIds = List.copyOf(rankedDocumentIds);
            citations = List.copyOf(citations);
            evidenceCitationIds = Set.copyOf(evidenceCitationIds);
        }
    }

    record Result(
            double recallAt5,
            double mrr,
            Double citationValidityRate,
            double noAnswerAccuracy,
            int answerableCount,
            int noAnswerCount,
            boolean formalGold,
            String evidenceStatus) {
    }
}
