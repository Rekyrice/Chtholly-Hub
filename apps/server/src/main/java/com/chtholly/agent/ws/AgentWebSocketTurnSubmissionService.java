package com.chtholly.agent.ws;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Coordinates admission and execution of one inbound WebSocket turn. */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentWebSocketTurnSubmissionService {

    private final AgentWebSocketTurnAdmissionService admissionService;
    private final AgentWebSocketAcceptedTurnRunner acceptedTurnRunner;

    /**
     * Creates the turn-submission facade.
     *
     * @param admissionService memory and lease admission service
     * @param acceptedTurnRunner accepted-turn runner
     */
    public AgentWebSocketTurnSubmissionService(
            AgentWebSocketTurnAdmissionService admissionService,
            AgentWebSocketAcceptedTurnRunner acceptedTurnRunner) {
        this.admissionService = Objects.requireNonNull(
                admissionService, "admissionService");
        this.acceptedTurnRunner = Objects.requireNonNull(
                acceptedTurnRunner, "acceptedTurnRunner");
    }

    void submit(
            AgentWebSocketConnectionRegistry.ConnectionContext connection,
            AgentWebSocketProtocolCodec.ChatRequest request) {
        admissionService.admit(connection, request).ifPresent(admission ->
                acceptedTurnRunner.run(connection, request, admission));
    }
}
