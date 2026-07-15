package com.chtholly.agent.anchor;

import com.chtholly.agent.CharacterSoulService;
import com.chtholly.agent.learning.InsightService;
import com.chtholly.agent.memory.AgentMemoryStore;
import com.chtholly.agent.memory.AgentTurn;
import com.chtholly.agent.state.BehaviorProb;
import com.chtholly.agent.state.CharacterState;
import com.chtholly.agent.state.CharacterStateService;
import com.chtholly.agent.state.Mood;
import com.chtholly.agent.state.Needs;
import com.chtholly.agent.state.Personality;
import com.chtholly.agent.state.Relationship;
import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnchorManagerTest {

    @Test
    void buildContextCollectsAvailableAnchors() {
        CharacterSoulService soulService = mock(CharacterSoulService.class);
        AgentMemoryStore memoryStore = mock(AgentMemoryStore.class);
        KnowledgeService knowledgeService = mock(KnowledgeService.class);
        InsightService insightService = mock(InsightService.class);
        CharacterStateService stateService = mock(CharacterStateService.class);
        CharacterState state = state(0.42, 8);

        when(soulService.getSoulContent()).thenReturn("identity");
        when(memoryStore.getTurns(7L, "ws-1")).thenReturn(List.of(AgentTurn.user("hello")));
        when(knowledgeService.getRelevantKnowledge(7L, "ws-1")).thenReturn(List.of("semantic"));
        when(insightService.getInsightTextsForUser(7L, 5, 500)).thenReturn(List.of("rule"));
        when(stateService.load(7L)).thenReturn(state);

        AnchorContext context = new AnchorManager(
                soulService,
                memoryStore,
                knowledgeService,
                insightService,
                stateService).buildContext(7L, "ws-1");

        assertThat(context.soul()).isEqualTo("identity");
        assertThat(context.episodic()).extracting(AgentTurn::content).containsExactly("hello");
        assertThat(context.semantic()).containsExactly("semantic");
        assertThat(context.procedural()).containsExactly("rule");
        assertThat(context.relational()).isSameAs(state);
    }

    @Test
    void buildContextFallsBackPerAnchorWithoutCascadeFailure() {
        CharacterSoulService soulService = mock(CharacterSoulService.class);
        AgentMemoryStore memoryStore = mock(AgentMemoryStore.class);
        KnowledgeService knowledgeService = mock(KnowledgeService.class);
        InsightService insightService = mock(InsightService.class);
        CharacterStateService stateService = mock(CharacterStateService.class);

        when(soulService.getSoulContent()).thenThrow(new IllegalStateException("identity down"));
        when(memoryStore.getTurns(7L, "ws-1")).thenThrow(new IllegalStateException("memory down"));
        when(knowledgeService.getRelevantKnowledge(7L, "ws-1")).thenReturn(List.of("semantic still works"));
        when(insightService.getInsightTextsForUser(7L, 5, 500)).thenThrow(new IllegalStateException("rules down"));
        when(stateService.load(7L)).thenThrow(new IllegalStateException("state down"));

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AnchorManager.class);
        Level originalLevel = logger.getLevel();
        AnchorContext context;
        logger.setLevel(Level.ERROR);
        try {
            context = new AnchorManager(
                    soulService,
                    memoryStore,
                    knowledgeService,
                    insightService,
                    stateService).buildContext(7L, "ws-1");
        } finally {
            logger.setLevel(originalLevel);
        }

        assertThat(context.soul()).isNotBlank();
        assertThat(context.episodic()).isEmpty();
        assertThat(context.semantic()).containsExactly("semantic still works");
        assertThat(context.procedural()).isEmpty();
        assertThat(context.relational()).isEqualTo(CharacterState.defaultState());
    }

    @Test
    void buildContextUsesEmptyEpisodicAnchorWhenMemoryStoreIsDisabled() {
        CharacterSoulService soulService = mock(CharacterSoulService.class);
        KnowledgeService knowledgeService = mock(KnowledgeService.class);
        InsightService insightService = mock(InsightService.class);
        CharacterStateService stateService = mock(CharacterStateService.class);
        CharacterState state = state(0.0, 0);

        when(soulService.getSoulContent()).thenReturn("identity");
        when(knowledgeService.getRelevantKnowledge(7L, "ws-1")).thenReturn(List.of());
        when(insightService.getInsightTextsForUser(7L, 5, 500)).thenReturn(List.of());
        when(stateService.load(7L)).thenReturn(state);

        AnchorContext context = new AnchorManager(
                soulService,
                (AgentMemoryStore) null,
                knowledgeService,
                insightService,
                stateService).buildContext(7L, "ws-1");

        assertThat(context.episodic()).isEmpty();
        assertThat(context.relational()).isSameAs(state);
    }

    @Test
    void buildContextUsesEmptyProceduralAnchorWhenInsightExtensionIsDisabled() {
        CharacterSoulService soulService = mock(CharacterSoulService.class);
        KnowledgeService knowledgeService = mock(KnowledgeService.class);
        CharacterStateService stateService = mock(CharacterStateService.class);
        CharacterState state = state(0.2, 3);

        when(soulService.getSoulContent()).thenReturn("identity");
        when(knowledgeService.getRelevantKnowledge(7L, "ws-1")).thenReturn(List.of("semantic"));
        when(stateService.load(7L)).thenReturn(state);

        AnchorContext context = new AnchorManager(
                soulService,
                new StaticListableBeanFactory().getBeanProvider(AgentMemoryStore.class),
                knowledgeService,
                new StaticListableBeanFactory().getBeanProvider(InsightService.class),
                stateService).buildContext(7L, "ws-1");

        assertThat(context.soul()).isEqualTo("identity");
        assertThat(context.semantic()).containsExactly("semantic");
        assertThat(context.procedural()).isEmpty();
        assertThat(context.relational()).isSameAs(state);
    }

    private CharacterState state(double intimacy, long interactionCount) {
        return new CharacterState(
                new Personality(0.7, 0.8, 0.5),
                new Mood(0.0, 0.5, 0.0),
                new Relationship(intimacy, interactionCount, Instant.parse("2026-07-03T00:00:00Z")),
                new Needs(0.0, 0.0, 0.0),
                new BehaviorProb(0.5, 0.3, 0.3));
    }
}
