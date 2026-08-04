package com.chtholly.relation.api;

import com.chtholly.auth.token.JwtService;
import com.chtholly.relation.service.RelationCounterQueryService;
import com.chtholly.relation.service.RelationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RelationControllerCounterTest {

    @Mock
    private RelationService relationService;
    @Mock
    private JwtService jwtService;
    @Mock
    private RelationCounterQueryService counterQueryService;

    private RelationController controller;

    @BeforeEach
    void setUp() {
        controller = new RelationController(
                relationService,
                jwtService,
                counterQueryService);
    }

    @Test
    void counterDelegatesToQueryService() {
        Map<String, Long> expected = Map.of("followings", 1L, "followers", 2L);
        when(counterQueryService.getCounters(10L)).thenReturn(expected);

        Map<String, Long> counters = controller.counter(10L);

        assertThat(counters).isSameAs(expected);
        verify(counterQueryService).getCounters(10L);
    }
}
