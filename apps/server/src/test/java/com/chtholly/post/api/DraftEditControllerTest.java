package com.chtholly.post.api;

import com.chtholly.auth.token.JwtService;
import com.chtholly.post.draftedit.DraftEditService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DraftEditControllerTest {

    @Test
    void createPinsAuthenticatedOwnerAndNeverAcceptsClientSkillVersion() {
        DraftEditService service = mock(DraftEditService.class);
        JwtService jwtService = mock(JwtService.class);
        Jwt jwt = mock(Jwt.class);
        String sha = "a".repeat(64);
        var expected = new DraftEditService.PreviewResult(
                "99", "42", "draft-edit", "v1", sha, "b".repeat(64),
                "c".repeat(64), "# candidate", "PENDING", Instant.now().plusSeconds(60));
        when(jwtService.extractUserId(jwt)).thenReturn(7L);
        when(service.createPreview(7L, 42L, "# base", sha, "润色")).thenReturn(expected);
        DraftEditController controller = new DraftEditController(service, jwtService);

        var result = controller.create(
                42L, new DraftEditController.CreatePreviewRequest("# base", sha, "润色"), jwt);

        assertThat(result.skillId()).isEqualTo("draft-edit");
        assertThat(result.skillVersion()).isEqualTo("v1");
        verify(service).createPreview(7L, 42L, "# base", sha, "润色");
    }

    @Test
    void confirmAndRejectForwardOnlyPreviewIdentityAndHash() {
        DraftEditService service = mock(DraftEditService.class);
        JwtService jwtService = mock(JwtService.class);
        Jwt jwt = mock(Jwt.class);
        String hash = "c".repeat(64);
        when(jwtService.extractUserId(jwt)).thenReturn(7L);
        when(service.confirm(7L, 42L, 99L, hash)).thenReturn(
                new DraftEditService.DecisionResult("99", "42", "APPLIED", "d".repeat(64), "/content.md"));
        when(service.reject(7L, 42L, 100L, hash)).thenReturn(
                new DraftEditService.DecisionResult("100", "42", "REJECTED", null, null));
        DraftEditController controller = new DraftEditController(service, jwtService);
        var request = new DraftEditController.DecisionRequest(hash);

        assertThat(controller.confirm(42L, 99L, request, jwt).status()).isEqualTo("APPLIED");
        assertThat(controller.reject(42L, 100L, request, jwt).status()).isEqualTo("REJECTED");
        verify(service).confirm(7L, 42L, 99L, hash);
        verify(service).reject(7L, 42L, 100L, hash);
    }
}
