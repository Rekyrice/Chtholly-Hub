package com.chtholly.agent.learning;

import com.chtholly.agent.memory.AgentTurn;
import com.chtholly.agent.memory.ProceduralMemoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InsightServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-03T01:00:00Z");

    private ObjectMapper objectMapper;
    private ProceduralMemoryService proceduralMemoryService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        proceduralMemoryService = mock(ProceduralMemoryService.class);
    }

    @Test
    void reflectOnConversationStoresGeneratedRulesInProceduralMemory() {
        InsightService service = service(prompt -> List.of(
                "Answer character questions with role names first",
                "Include score and airing date when users ask for ratings",
                "   "));
        when(proceduralMemoryService.getTopRules(42L, 15, 2_000)).thenReturn(List.of());

        service.reflectOnConversation(42L, longConversation());

        verify(proceduralMemoryService).storeRule(42L, "Answer character questions with role names first");
        verify(proceduralMemoryService).storeRule(42L, "Include score and airing date when users ask for ratings");
    }

    @Test
    void reflectOnConversationSkipsShortConversation() {
        AtomicInteger calls = new AtomicInteger();
        InsightService service = service(prompt -> {
            calls.incrementAndGet();
            return List.of("unused");
        });

        service.reflectOnConversation(42L, List.of(AgentTurn.user("too short")));

        assertThat(calls).hasValue(0);
        verify(proceduralMemoryService, never()).storeRule(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void reflectPromptIncludesExistingProceduralRules() {
        AtomicReference<String> promptRef = new AtomicReference<>();
        InsightService service = service(prompt -> {
            promptRef.set(prompt);
            return List.of();
        });
        when(proceduralMemoryService.getTopRules(42L, 15, 2_000))
                .thenReturn(List.of("Existing procedural rule"));

        service.reflectOnConversation(42L, longConversation());

        assertThat(promptRef.get()).contains("Existing procedural rule");
    }

    private InsightService service(Function<String, List<String>> generator) {
        return new InsightService(
                objectMapper,
                generator,
                Clock.fixed(NOW, ZoneOffset.UTC),
                proceduralMemoryService);
    }

    private List<AgentTurn> longConversation() {
        return List.of(
                AgentTurn.user("Which characters are in it?"),
                AgentTurn.assistant("List main characters first."),
                AgentTurn.user("What about voice actors?"),
                AgentTurn.assistant("Add voice actor info."),
                AgentTurn.user("What is the rating?"),
                AgentTurn.assistant("Add score and airing details.")
        );
    }
}
