package com.chtholly.post.api;

import com.chtholly.admin.security.BannedUserFilter;
import com.chtholly.agent.draftedit.DraftEditController;
import com.chtholly.agent.draftedit.DraftEditService;
import com.chtholly.auth.config.SecurityConfig;
import com.chtholly.auth.token.JwtService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = DraftEditController.class,
        properties = {
                "llm.enabled=true",
                "cors.allowed-origins=http://localhost:3000",
                "storage.type=oss"
        })
@Import(SecurityConfig.class)
class DraftEditControllerSecurityTest {

    private static final String SHA256 = "a".repeat(64);

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DraftEditService service;

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
        when(jwtService.extractUserId(any())).thenReturn(9L);
    }

    @Test
    void createPreview_whenAnonymous_thenReturnsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/posts/42/draft-edit/previews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateBody()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    @Test
    void createPreview_whenBaseDigestIsInvalid_thenReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/posts/42/draft-edit/previews")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseContent":"正文","baseContentSha256":"not-a-sha","instruction":"精简正文"}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void confirm_whenAuthenticated_thenPassesOnlyPinnedIdentityAndHash() throws Exception {
        mockMvc.perform(post("/api/v1/posts/42/draft-edit/previews/77/confirm")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"previewHash\":\"" + SHA256 + "\"}"))
                .andExpect(status().isOk());

        verify(service).confirm(9L, 42L, 77L, SHA256);
    }

    @Test
    void reject_whenAuthenticated_thenPassesOnlyPinnedIdentityAndHash() throws Exception {
        mockMvc.perform(post("/api/v1/posts/42/draft-edit/previews/77/reject")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"previewHash\":\"" + SHA256 + "\"}"))
                .andExpect(status().isOk());

        verify(service).reject(9L, 42L, 77L, SHA256);
    }

    private String validCreateBody() {
        return "{\"baseContent\":\"正文\",\"baseContentSha256\":\""
                + SHA256
                + "\",\"instruction\":\"精简正文\"}";
    }
}
