package com.chtholly.agent.context;

import com.chtholly.agent.AgentTool;
import com.chtholly.agent.anchor.AnchorContext;
import com.chtholly.agent.anchor.AnchorManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Assembles the system prompt from an anchor snapshot and ordered contributors. */
@Slf4j
@Service
public class ContextEngine {

    private final AnchorManager anchorManager;
    private final List<ContextContributor> contributors;

    /**
     * Creates the context engine and validates contributor identity and ordering.
     *
     * @param anchorManager anchor snapshot manager
     * @param contributors prompt contributors
     * @throws IllegalArgumentException when contributor names or orders are duplicated
     */
    ContextEngine(AnchorManager anchorManager, List<ContextContributor> contributors) {
        this.anchorManager = anchorManager;
        validateContributors(contributors);
        this.contributors = contributors.stream()
                .sorted(Comparator.comparingInt(ContextContributor::order))
                .toList();
    }

    /**
     * Builds the complete system prompt with no tools or formatted history.
     *
     * @param userId authenticated user ID
     * @param sessionId conversation session ID
     * @param pageContext current page context
     * @param userQuestion current user question
     * @return complete system prompt
     */
    public String buildSystemPrompt(long userId, String sessionId, String pageContext, String userQuestion) {
        return buildSystemPrompt(userId, sessionId, pageContext, List.of(), "", userQuestion);
    }

    /**
     * Builds the complete ReAct system prompt from an anchor snapshot.
     *
     * @param userId authenticated user ID
     * @param sessionId conversation session ID
     * @param pageContext current page context
     * @param tools tools available to the agent loop
     * @param conversationHistory recent formatted conversation
     * @param userQuestion current user question
     * @return complete system prompt
     */
    public String buildSystemPrompt(long userId, String sessionId, String pageContext,
                                    Iterable<AgentTool> tools, String conversationHistory,
                                    String userQuestion) {
        AnchorContext anchors = anchorManager.buildContext(userId, sessionId);
        ContextRequest request = new ContextRequest(
                userId, sessionId, pageContext, tools, conversationHistory, userQuestion, anchors);

        return contributors.stream()
                .map(contributor -> safeContribution(contributor, request))
                .filter(contribution -> !contribution.isEmpty())
                .map(contribution -> contribution.content().strip())
                .collect(java.util.stream.Collectors.joining("\n\n"));
    }

    private ContextContribution safeContribution(ContextContributor contributor, ContextRequest request) {
        try {
            ContextContribution contribution = contributor.contribute(request);
            return contribution == null
                    ? ContextContribution.empty(contributor.name(), contributor.order(), true)
                    : contribution;
        } catch (RuntimeException e) {
            log.warn("Context contributor failed: {}", contributor.name(), e);
            return ContextContribution.empty(contributor.name(), contributor.order(), true);
        }
    }

    private static void validateContributors(List<ContextContributor> contributors) {
        if (contributors == null) {
            throw new IllegalArgumentException("Context contributors must not be null");
        }
        Set<String> names = new HashSet<>();
        Set<Integer> orders = new HashSet<>();
        for (ContextContributor contributor : contributors) {
            if (contributor == null) {
                throw new IllegalArgumentException("Context contributor must not be null");
            }
            if (!names.add(contributor.name())) {
                throw new IllegalArgumentException("Duplicate context contributor name: " + contributor.name());
            }
            if (!orders.add(contributor.order())) {
                throw new IllegalArgumentException("Duplicate context contributor order: " + contributor.order());
            }
        }
    }
}
