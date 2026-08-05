package com.chtholly.agent.runtime;

import com.chtholly.agent.evidence.Evidence;
import com.chtholly.agent.evidence.EvidenceSet;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Holds evidence identity, canonical transcript entries, and web discovery state for one turn. */
@Slf4j
public final class AgentEvidenceTracker {

    private EvidenceSet evidenceSet;
    private final boolean initialRequired;
    private final ObjectMapper objectMapper;
    private boolean dynamicAdded;
    private WebResearchRequirement webRequirement = WebResearchRequirement.NONE;
    private Set<String> pendingWebCandidateUrls = Set.of();
    private Set<String> failedWebCandidateUrls = Set.of();
    private int emptyWebSearchAttempts;
    private final Map<Integer, String> canonicalEvidenceObservations = new LinkedHashMap<>();

    /**
     * Creates an isolated tracker for one loop execution.
     *
     * @param evidenceSet initial immutable evidence
     * @param initialRequired whether initial context requires evidence
     * @param objectMapper JSON parser for web discovery envelopes
     */
    public AgentEvidenceTracker(
            EvidenceSet evidenceSet,
            boolean initialRequired,
            ObjectMapper objectMapper) {
        this.evidenceSet = evidenceSet == null ? EvidenceSet.empty() : evidenceSet;
        this.initialRequired = initialRequired;
        this.objectMapper = objectMapper;
    }

    /**
     * Merges candidates while retaining stable citation identity for changed evidence.
     *
     * @param candidates tool-discovered evidence
     * @return prompt rendering of newly visible or changed items
     */
    public String merge(List<Evidence> candidates) {
        List<Evidence> previousItems = evidenceSet.items();
        EvidenceSet merged = evidenceSet.append(candidates);
        if (merged == evidenceSet) {
            return "";
        }
        List<Evidence> changedItems = new ArrayList<>();
        List<Evidence> mergedItems = merged.items();
        for (int index = 0; index < mergedItems.size(); index++) {
            if (index >= previousItems.size()
                    || !mergedItems.get(index).equals(previousItems.get(index))) {
                changedItems.add(mergedItems.get(index));
            }
        }
        evidenceSet = merged;
        dynamicAdded = true;
        return evidenceSet.renderForPromptItems(changedItems);
    }

    /** @return current immutable evidence set. */
    public EvidenceSet evidenceSet() {
        return evidenceSet;
    }

    /** @return whether the final answer must cite evidence. */
    public boolean evidenceRequired() {
        return initialRequired || dynamicAdded;
    }

    /**
     * Records the canonical observation text for a dynamic-evidence transcript entry.
     *
     * @param transcriptIndex transcript entry index
     * @param canonicalObservation observation without superseded evidence rendering
     */
    public void recordEvidenceObservation(int transcriptIndex, String canonicalObservation) {
        if (transcriptIndex >= 0 && canonicalObservation != null) {
            canonicalEvidenceObservations.put(transcriptIndex, canonicalObservation);
        }
    }

    /**
     * Replaces dynamic-evidence transcript entries with their canonical observations.
     *
     * @param transcript current loop transcript
     * @return immutable final-answer transcript
     */
    public List<String> transcriptForFinalAnswer(List<String> transcript) {
        if (transcript == null || transcript.isEmpty() || canonicalEvidenceObservations.isEmpty()) {
            return transcript == null ? List.of() : List.copyOf(transcript);
        }
        List<String> canonical = new ArrayList<>(transcript);
        for (Map.Entry<Integer, String> replacement : canonicalEvidenceObservations.entrySet()) {
            if (replacement.getKey() < canonical.size()) {
                canonical.set(replacement.getKey(), replacement.getValue());
            }
        }
        return List.copyOf(canonical);
    }

    /**
     * Records a successful web search and distinguishes retryable discovery from fetchable URLs.
     *
     * @param observation web search observation envelope
     */
    public void recordSuccessfulWebSearch(String observation) {
        WebSearchDiscovery discovery = parseWebSearchDiscovery(observation);
        if (!discovery.valid() || discovery.candidateUrls().isEmpty()) {
            if (pendingWebCandidateUrls.isEmpty()) {
                emptyWebSearchAttempts++;
                webRequirement = emptyWebSearchAttempts == 1
                        ? WebResearchRequirement.SEARCH_RETRY_REQUIRED
                        : WebResearchRequirement.NONE;
            } else {
                refreshFetchRequirement();
            }
            return;
        }
        emptyWebSearchAttempts = 0;
        LinkedHashSet<String> accumulated = new LinkedHashSet<>(pendingWebCandidateUrls);
        accumulated.addAll(discovery.candidateUrls());
        pendingWebCandidateUrls = Set.copyOf(accumulated);
        refreshFetchRequirement();
    }

    /**
     * Clears fetch-required state only when the requested URL came from search and the accepted
     * public evidence is bound to the final URL after redirects.
     *
     * @param observation web fetch observation envelope
     * @param evidence fetched evidence
     */
    public void recordSuccessfulWebFetch(String observation, List<Evidence> evidence) {
        WebFetchIdentity fetchIdentity = parseWebFetchIdentity(observation);
        if (webRequirement == WebResearchRequirement.FETCH_REQUIRED
                && !fetchIdentity.requestedUrl().isBlank()
                && pendingWebCandidateUrls.contains(fetchIdentity.requestedUrl())
                && !fetchIdentity.finalUrl().isBlank()
                && hasAcceptedWebEvidence(fetchIdentity.finalUrl(), evidence)) {
            webRequirement = WebResearchRequirement.NONE;
            pendingWebCandidateUrls = Set.of();
            failedWebCandidateUrls = Set.of();
        }
    }

    /** Updates web completion state for successful, retryable, and terminal tool outcomes. */
    void recordWebToolResult(
            String toolName,
            Map<String, Object> input,
            AgentToolResult result) {
        if (result == null || result.status() == null) {
            return;
        }
        if ("web_search".equals(toolName)) {
            recordWebSearchResult(result);
            return;
        }
        if ("web_fetch".equals(toolName)) {
            recordWebFetchResult(input, result);
        }
    }

    private void recordWebSearchResult(AgentToolResult result) {
        if (result.status() == AgentToolResult.Status.SUCCESS) {
            recordSuccessfulWebSearch(result.observation());
            return;
        }
        if (result.status() == AgentToolResult.Status.VALIDATION_ERROR) {
            webRequirement = pendingWebCandidateUrls.isEmpty()
                    ? WebResearchRequirement.SEARCH_RETRY_REQUIRED
                    : WebResearchRequirement.FETCH_REQUIRED;
            return;
        }
        if (webRequirement == WebResearchRequirement.SEARCH_RETRY_REQUIRED) {
            webRequirement = WebResearchRequirement.NONE;
        }
    }

    private void recordWebFetchResult(
            Map<String, Object> input,
            AgentToolResult result) {
        if (result.status() == AgentToolResult.Status.SUCCESS) {
            recordSuccessfulWebFetch(result.observation(), result.evidence());
            return;
        }
        if (result.status() == AgentToolResult.Status.VALIDATION_ERROR
                || webRequirement != WebResearchRequirement.FETCH_REQUIRED) {
            return;
        }
        Object rawUrl = input == null ? null : input.get("url");
        String requestedUrl = normalizeResearchUrl(rawUrl == null ? "" : String.valueOf(rawUrl));
        if (requestedUrl.isBlank() || !pendingWebCandidateUrls.contains(requestedUrl)) {
            return;
        }
        LinkedHashSet<String> failed = new LinkedHashSet<>(failedWebCandidateUrls);
        failed.add(requestedUrl);
        failedWebCandidateUrls = Set.copyOf(failed);
        refreshFetchRequirement();
    }

    private void refreshFetchRequirement() {
        boolean remainingCandidate = pendingWebCandidateUrls.stream()
                .anyMatch(candidate -> !failedWebCandidateUrls.contains(candidate));
        webRequirement = remainingCandidate
                ? WebResearchRequirement.FETCH_REQUIRED
                : WebResearchRequirement.NONE;
    }

    private boolean hasAcceptedWebEvidence(String finalUrl, List<Evidence> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return false;
        }
        return evidence.stream().anyMatch(candidate -> candidate != null
                && candidate.permissions().equals(Set.of("PUBLIC"))
                && "WEB".equals(candidate.sourceType())
                && "web_fetch".equals(candidate.retrievalSource())
                && finalUrl.equals(normalizeResearchUrl(candidate.sourceId()))
                && finalUrl.equals(normalizeResearchUrl(candidate.documentId())));
    }

    /** @return explicit next web research requirement. */
    public WebResearchRequirement webResearchRequirement() {
        return webRequirement;
    }

    private WebSearchDiscovery parseWebSearchDiscovery(String observation) {
        try {
            JsonNode root = objectMapper.readTree(observation == null ? "" : observation);
            if (root == null || !"web_search_results".equals(root.path("kind").asText())) {
                return WebSearchDiscovery.invalid();
            }
            LinkedHashSet<String> urls = new LinkedHashSet<>();
            JsonNode results = root.path("results");
            if (results.isArray()) {
                for (JsonNode result : results) {
                    String normalized = normalizeResearchUrl(result.path("url").asText(""));
                    if (!normalized.isBlank()) {
                        urls.add(normalized);
                    }
                }
            }
            return new WebSearchDiscovery(true, Set.copyOf(urls));
        } catch (Exception exception) {
            log.debug("Ignoring malformed web_search observation envelope", exception);
            return WebSearchDiscovery.invalid();
        }
    }

    private WebFetchIdentity parseWebFetchIdentity(String observation) {
        try {
            JsonNode root = objectMapper.readTree(observation == null ? "" : observation);
            if (root == null || !"web_fetched_page".equals(root.path("kind").asText())) {
                return WebFetchIdentity.invalid();
            }
            return new WebFetchIdentity(
                    normalizeResearchUrl(root.path("requestedUrl").asText("")),
                    normalizeResearchUrl(root.path("finalUrl").asText("")));
        } catch (Exception exception) {
            log.debug("Ignoring malformed web_fetch observation envelope", exception);
            return WebFetchIdentity.invalid();
        }
    }

    private static String normalizeResearchUrl(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl == null ? "" : rawUrl.strip()).normalize();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            if (!("http".equals(scheme) || "https".equals(scheme))
                    || host == null
                    || host.isBlank()
                    || uri.getUserInfo() != null) {
                return "";
            }
            int port = uri.getPort();
            if (("http".equals(scheme) && port == 80)
                    || ("https".equals(scheme) && port == 443)) {
                port = -1;
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            String authorityHost = normalizedHost.contains(":")
                    ? "[" + normalizedHost + "]"
                    : normalizedHost;
            String path = uri.getRawPath();
            if (path == null || path.isBlank()) {
                path = "/";
            }
            StringBuilder normalized = new StringBuilder(scheme)
                    .append("://")
                    .append(authorityHost);
            if (port >= 0) {
                normalized.append(':').append(port);
            }
            normalized.append(path);
            if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) {
                normalized.append('?').append(uri.getRawQuery());
            }
            return normalized.toString();
        } catch (IllegalArgumentException exception) {
            return "";
        }
    }

    /** Explicit web research state that prevents ambiguous completion loops. */
    public enum WebResearchRequirement {
        NONE,
        SEARCH_RETRY_REQUIRED,
        FETCH_REQUIRED
    }

    private record WebSearchDiscovery(boolean valid, Set<String> candidateUrls) {

        private WebSearchDiscovery {
            candidateUrls = candidateUrls == null ? Set.of() : Set.copyOf(candidateUrls);
        }

        private static WebSearchDiscovery invalid() {
            return new WebSearchDiscovery(false, Set.of());
        }
    }

    private record WebFetchIdentity(String requestedUrl, String finalUrl) {

        private static WebFetchIdentity invalid() {
            return new WebFetchIdentity("", "");
        }
    }
}
