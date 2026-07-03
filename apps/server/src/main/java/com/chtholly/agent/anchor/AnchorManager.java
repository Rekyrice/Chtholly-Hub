package com.chtholly.agent.anchor;

import com.chtholly.agent.CharacterSoulService;
import com.chtholly.agent.memory.AgentMemoryStore;
import com.chtholly.agent.state.CharacterState;
import com.chtholly.agent.state.CharacterStateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Manages the five identity anchors with independent failure domains.
 *
 * <p>Each anchor is isolated so a Redis, knowledge, or rules failure does not
 * prevent the remaining context layers from being assembled.
 */
@Slf4j
@Service
public class AnchorManager {

    private static final String IDENTITY_FALLBACK = "你是珂朵莉，一个住在网站里的角色。";

    private final CharacterSoulService soulService;
    private final AgentMemoryStore memoryStore;
    private final KnowledgeService knowledgeService;
    private final ProceduralMemoryService proceduralService;
    private final CharacterStateService stateService;

    public AnchorManager(CharacterSoulService soulService,
                         AgentMemoryStore memoryStore,
                         KnowledgeService knowledgeService,
                         ProceduralMemoryService proceduralService,
                         CharacterStateService stateService) {
        this.soulService = soulService;
        this.memoryStore = memoryStore;
        this.knowledgeService = knowledgeService;
        this.proceduralService = proceduralService;
        this.stateService = stateService;
    }

    /**
     * Builds context from all available anchors.
     *
     * @param userId    Authenticated user ID.
     * @param sessionId Conversation session ID.
     * @return Anchor context assembled with per-anchor fallbacks.
     */
    public AnchorContext buildContext(long userId, String sessionId) {
        AnchorContext.Builder builder = AnchorContext.builder();

        try {
            builder.soul(soulService.getSoulContent());
        } catch (Exception e) {
            log.warn("Identity anchor failed, using fallback", e);
            builder.soul(IDENTITY_FALLBACK);
        }

        try {
            builder.episodic(memoryStore.getTurns(userId, sessionId));
        } catch (Exception e) {
            log.warn("Episodic anchor failed userId={}, sessionId={}", userId, sessionId, e);
            builder.episodic(List.of());
        }

        try {
            builder.semantic(knowledgeService.getRelevantKnowledge(userId, sessionId));
        } catch (Exception e) {
            log.warn("Semantic anchor failed userId={}, sessionId={}", userId, sessionId, e);
            builder.semantic(List.of());
        }

        try {
            builder.procedural(proceduralService.getTopRules(userId, 5, 500));
        } catch (Exception e) {
            log.warn("Procedural anchor failed userId={}", userId, e);
            builder.procedural(List.of());
        }

        try {
            builder.relational(stateService.load(userId));
        } catch (Exception e) {
            log.warn("Relational anchor failed userId={}", userId, e);
            builder.relational(CharacterState.defaultState());
        }

        return builder.build();
    }
}
