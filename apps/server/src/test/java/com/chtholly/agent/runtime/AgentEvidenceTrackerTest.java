package com.chtholly.agent.runtime;

import com.chtholly.agent.evidence.Evidence;
import com.chtholly.agent.evidence.EvidenceSet;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEvidenceTrackerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void distinguishesSearchRetryFromFetchRequiredAndClearsOnlyMatchingEvidenceFetch() throws Exception {
        AgentEvidenceTracker tracker = new AgentEvidenceTracker(
                EvidenceSet.empty(), false, objectMapper);

        tracker.recordSuccessfulWebSearch("malformed");
        assertThat(tracker.webResearchRequirement())
                .isEqualTo(AgentEvidenceTracker.WebResearchRequirement.SEARCH_RETRY_REQUIRED);

        tracker.recordSuccessfulWebSearch(webSearchObservation("HTTPS://Example.COM:443/article"));
        assertThat(tracker.webResearchRequirement())
                .isEqualTo(AgentEvidenceTracker.WebResearchRequirement.FETCH_REQUIRED);

        Evidence evidence = Evidence.fromWebPage(
                "https://example.com/article", "Article", "hash", "verified excerpt");
        tracker.recordSuccessfulWebFetch(
                webFetchObservation("https://example.com/article"), List.of(evidence));

        assertThat(tracker.webResearchRequirement())
                .isEqualTo(AgentEvidenceTracker.WebResearchRequirement.NONE);
    }

    @Test
    void validSearchWithoutCandidatesExplicitlyRequiresAnotherSearch() throws Exception {
        AgentEvidenceTracker tracker = new AgentEvidenceTracker(
                EvidenceSet.empty(), false, objectMapper);

        tracker.recordSuccessfulWebSearch(webSearchObservation());

        assertThat(tracker.webResearchRequirement())
                .isEqualTo(AgentEvidenceTracker.WebResearchRequirement.SEARCH_RETRY_REQUIRED);
    }

    @Test
    void secondEmptySearchExhaustsTheRequiredRetry() throws Exception {
        AgentEvidenceTracker tracker = new AgentEvidenceTracker(
                EvidenceSet.empty(), false, objectMapper);

        tracker.recordSuccessfulWebSearch(webSearchObservation());
        tracker.recordSuccessfulWebSearch(webSearchObservation());

        assertThat(tracker.webResearchRequirement())
                .isEqualTo(AgentEvidenceTracker.WebResearchRequirement.NONE);
    }

    @Test
    void redirectedFetchUsesRequestedUrlForDiscoveryAndFinalUrlForEvidence() throws Exception {
        AgentEvidenceTracker tracker = new AgentEvidenceTracker(
                EvidenceSet.empty(), false, objectMapper);
        tracker.recordSuccessfulWebSearch(webSearchObservation("https://example.com/article"));
        Evidence redirectedEvidence = Evidence.fromWebPage(
                "HTTPS://CONTENT.EXAMPLE:443/articles/final",
                "Redirected article",
                "hash",
                "verified excerpt");

        tracker.recordSuccessfulWebFetch(
                webFetchObservation(
                        "HTTPS://EXAMPLE.COM:443/article",
                        "https://content.example/articles/final"),
                List.of(redirectedEvidence));

        assertThat(tracker.webResearchRequirement())
                .isEqualTo(AgentEvidenceTracker.WebResearchRequirement.NONE);
    }

    @Test
    void redirectedFetchStillRejectsUnknownRequestedUrlOrMismatchedFinalEvidence() throws Exception {
        AgentEvidenceTracker tracker = new AgentEvidenceTracker(
                EvidenceSet.empty(), false, objectMapper);
        tracker.recordSuccessfulWebSearch(webSearchObservation("https://example.com/article"));
        Evidence finalEvidence = Evidence.fromWebPage(
                "https://content.example/final", "Final", "hash", "verified excerpt");

        tracker.recordSuccessfulWebFetch(
                webFetchObservation(
                        "https://unknown.example/article",
                        "https://content.example/final"),
                List.of(finalEvidence));
        assertThat(tracker.webResearchRequirement())
                .isEqualTo(AgentEvidenceTracker.WebResearchRequirement.FETCH_REQUIRED);

        tracker.recordSuccessfulWebFetch(
                webFetchObservation(
                        "https://example.com/article",
                        "https://content.example/other"),
                List.of(finalEvidence));
        assertThat(tracker.webResearchRequirement())
                .isEqualTo(AgentEvidenceTracker.WebResearchRequirement.FETCH_REQUIRED);
    }

    @Test
    void nonPublicOrUrlMismatchedEvidenceCannotCompleteAWebFetch() throws Exception {
        AgentEvidenceTracker tracker = new AgentEvidenceTracker(
                EvidenceSet.empty(), false, objectMapper);
        tracker.recordSuccessfulWebSearch(webSearchObservation("https://example.com/article"));
        Evidence publicEvidence = Evidence.fromWebPage(
                "https://example.com/article", "Article", "hash", "verified excerpt");
        Evidence nonPublicEvidence = new Evidence(
                publicEvidence.evidenceId(),
                publicEvidence.sourceType(),
                publicEvidence.sourceId(),
                publicEvidence.documentId(),
                publicEvidence.chunkId(),
                publicEvidence.title(),
                publicEvidence.retrievalSource(),
                publicEvidence.sourceVersion(),
                publicEvidence.sourceHash(),
                publicEvidence.excerpt(),
                publicEvidence.rank(),
                publicEvidence.trust(),
                Set.of("PRIVATE"),
                publicEvidence.citationId());

        tracker.recordSuccessfulWebFetch(
                webFetchObservation("https://example.com/article"), List.of(nonPublicEvidence));
        assertThat(tracker.webResearchRequirement())
                .isEqualTo(AgentEvidenceTracker.WebResearchRequirement.FETCH_REQUIRED);

        Evidence mismatched = Evidence.fromWebPage(
                "https://other.example/article", "Other", "hash-2", "other excerpt");
        tracker.recordSuccessfulWebFetch(
                webFetchObservation("https://example.com/article"), List.of(mismatched));
        assertThat(tracker.webResearchRequirement())
                .isEqualTo(AgentEvidenceTracker.WebResearchRequirement.FETCH_REQUIRED);
    }

    private String webSearchObservation(String... urls) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("kind", "web_search_results");
        var results = root.putArray("results");
        for (String url : urls) {
            results.addObject().put("url", url);
        }
        return objectMapper.writeValueAsString(root);
    }

    private String webFetchObservation(String requestedUrl) throws Exception {
        return webFetchObservation(requestedUrl, requestedUrl);
    }

    private String webFetchObservation(String requestedUrl, String finalUrl) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("kind", "web_fetched_page");
        root.put("requestedUrl", requestedUrl);
        root.put("finalUrl", finalUrl);
        return objectMapper.writeValueAsString(root);
    }
}
