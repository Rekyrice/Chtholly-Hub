package com.chtholly.agent.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

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
}
