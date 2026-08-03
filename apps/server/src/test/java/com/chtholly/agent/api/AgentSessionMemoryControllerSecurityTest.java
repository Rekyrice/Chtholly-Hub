package com.chtholly.agent.api;

import com.chtholly.admin.security.BannedUserFilter;
import com.chtholly.agent.memory.AgentMemoryStore;
import com.chtholly.auth.config.SecurityConfig;
import com.chtholly.auth.token.JwtService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = AgentSessionMemoryController.class,
        properties = {
                "llm.enabled=true",
                "cors.allowed-origins=http://localhost:3000",
                "storage.type=oss"
        })
@Import(SecurityConfig.class)
class AgentSessionMemoryControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AgentMemoryStore memoryStore;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private BannedUserFilter bannedUserFilter;

    @MockBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() throws Exception {
        doAnswer(invocation -> {
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(bannedUserFilter).doFilter(any(), any(), any());
        when(jwtService.extractUserId(any())).thenAnswer(invocation -> {
            Jwt authenticatedJwt = invocation.getArgument(0);
            Number userId = authenticatedJwt.getClaim("uid");
            return userId.longValue();
        });
    }

    @Test
    void clearMemory_whenAnonymous_thenIsRejected() throws Exception {
        mockMvc.perform(delete("/api/v1/agent/sessions/sess-owned/memory"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));

        verifyNoInteractions(memoryStore);
    }

    @Test
    void clearMemory_whenAuthenticated_thenUsesJwtOwnerAndReturnsNoContent() throws Exception {
        mockMvc.perform(delete("/api/v1/agent/sessions/sess-owned/memory")
                        .with(jwt().jwt(token -> token.claim("uid", 73L))))
                .andExpect(status().isNoContent());

        verify(memoryStore).clearMemory(73L, "sess-owned");
    }

    @Test
    void clearMemory_whenSessionIdIsInvalid_thenReturnsBadRequestWithoutClearing() throws Exception {
        mockMvc.perform(delete("/api/v1/agent/sessions/bad!id/memory")
                        .with(jwt().jwt(token -> token.claim("uid", 73L))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(memoryStore);
    }

    @Test
    void clearMemory_whenRedisCannotConfirmDeletion_thenDoesNotReturnNoContent() throws Exception {
        doThrow(new IllegalStateException("redis unavailable"))
                .when(memoryStore).clearMemory(73L, "sess-owned");

        mockMvc.perform(delete("/api/v1/agent/sessions/sess-owned/memory")
                        .with(jwt().jwt(token -> token.claim("uid", 73L))))
                .andExpect(status().is5xxServerError());
    }
}
