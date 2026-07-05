package com.chtholly.agent.eval;

import com.chtholly.agent.AgentEvent;
import com.chtholly.agent.ChthollyAgent;
import java.util.ArrayList;
import java.util.List;

/**
 * Adapter that runs the real ChthollyAgent and extracts the final WebSocket-style answer.
 */
public class ChthollyAgentResponder implements AgentResponder {

    private final ChthollyAgent agent;

    public ChthollyAgentResponder(ChthollyAgent agent) {
        this.agent = agent;
    }

    @Override
    public String answer(EvaluationAgentRequest request) {
        List<AgentEvent> events = new ArrayList<>();
        agent.run(
                request.question().text(),
                request.userId(),
                null,
                "eval-" + request.question().id(),
                "eval:" + request.question().category(),
                events::add);
        return events.stream()
                .filter(event -> "final".equals(event.type()) || "error".equals(event.type()))
                .reduce((first, second) -> second)
                .map(event -> {
                    String finalContent = event.data().path("content").asText();
                    if (!finalContent.isBlank()) {
                        return finalContent;
                    }
                    return event.data().path("message").asText();
                })
                .orElse("");
    }
}
