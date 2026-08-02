package com.chtholly.agent.runtime;

import com.chtholly.agent.evidence.Evidence;
import com.chtholly.agent.evidence.EvidenceSet;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class AgentLoopResultTest {

    @Test
    void canonicalConstructorRejectsNullStatus() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new AgentLoopResult(null, List.of(), "failed", -1, 0));
    }

    @Test
    void canonicalConstructorRejectsInvalidFinalReadyMetadata() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new AgentLoopResult(
                        AgentLoopResult.Status.FINAL_READY,
                        List.of(),
                        null,
                        -1,
                        0));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new AgentLoopResult(
                        AgentLoopResult.Status.FINAL_READY,
                        List.of(),
                        null,
                        0,
                        -1));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new AgentLoopResult(
                        AgentLoopResult.Status.FINAL_READY,
                        List.of(),
                        "unexpected",
                        0,
                        0));
    }

    @Test
    void canonicalConstructorRejectsNonFinalMetadata() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new AgentLoopResult(
                        AgentLoopResult.Status.LLM_ERROR,
                        List.of(),
                        "failed",
                        0,
                        0));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new AgentLoopResult(
                        AgentLoopResult.Status.LLM_ERROR,
                        List.of(),
                        "failed",
                        -1,
                        1));
    }

    @Test
    void canonicalConstructorRejectsBlankTerminalError() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new AgentLoopResult(
                        AgentLoopResult.Status.MAX_STEPS,
                        List.of(),
                        null,
                        -1,
                        0));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new AgentLoopResult(
                        AgentLoopResult.Status.MAX_STEPS,
                        List.of(),
                        "  ",
                        -1,
                        0));
    }

    @Test
    void everyTerminalResultCarriesImmutableFinalEvidenceSnapshot() {
        Evidence evidence = Evidence.fromWebPage(
                "https://example.com/article", "Article", "hash", "excerpt");
        EvidenceSet evidenceSet = EvidenceSet.of(List.of(evidence), Set.of("PUBLIC"));

        AgentLoopResult ready = AgentLoopResult.finalReady(List.of("transcript"), 1, 2,
                evidenceSet, true);
        AgentLoopResult terminal = AgentLoopResult.terminal(
                AgentLoopResult.Status.MAX_STEPS, List.of(), "failed", evidenceSet, true);

        assertThat(ready.evidenceSet()).isSameAs(evidenceSet);
        assertThat(ready.evidenceRequired()).isTrue();
        assertThat(terminal.evidenceSet()).isSameAs(evidenceSet);
        assertThat(terminal.evidenceRequired()).isTrue();
    }
}
