package com.chtholly.admin.api;

import com.chtholly.admin.role.RequireRole;
import com.chtholly.admin.role.Role;
import com.chtholly.relation.outbox.RelationOutboxReplayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminRelationRecoveryControllerTest {

    @Mock
    private RelationOutboxReplayService replayService;

    private AdminRelationRecoveryController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminRelationRecoveryController(replayService);
    }

    @Test
    void controllerRequiresAdminRole() {
        RequireRole annotation = AdminRelationRecoveryController.class.getAnnotation(RequireRole.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).isEqualTo(Role.ADMIN);
    }

    @Test
    void replayIdDelegatesExactlyOnce() {
        when(replayService.replayId(42L)).thenReturn(1);

        assertThat(controller.replayId(42L)).containsEntry("processed", 1);

        verify(replayService).replayId(42L);
    }

    @Test
    void replayRangeDelegatesExactlyOnce() {
        when(replayService.replayRange(10L, 20L)).thenReturn(2);

        assertThat(controller.replayRange(10L, 20L)).containsEntry("processed", 2);

        verify(replayService).replayRange(10L, 20L);
    }
}
