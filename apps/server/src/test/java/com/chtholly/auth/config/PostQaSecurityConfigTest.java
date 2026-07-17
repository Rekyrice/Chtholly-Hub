package com.chtholly.auth.config;

import com.chtholly.admin.security.BannedUserFilter;
import com.chtholly.llm.rag.RagIndexService;
import com.chtholly.llm.rag.RagQueryService;
import com.chtholly.post.api.PostRagController;
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
import reactor.core.publisher.Flux;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = PostRagController.class,
        properties = {
                "llm.enabled=true",
                "cors.allowed-origins=http://localhost:3000",
                "storage.type=oss"
        }
)
@Import(SecurityConfig.class)
class PostQaSecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RagIndexService indexService;

    @MockBean
    private RagQueryService queryService;

    @MockBean
    private BannedUserFilter bannedUserFilter;

    @MockBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void passThroughBannedUserFilter() throws Exception {
        doAnswer(invocation -> {
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(bannedUserFilter).doFilter(any(), any(), any());
    }

    @Test
    void postQuestionStreamIsPublicLikeTheLegacyGetEndpoint() throws Exception {
        when(queryService.streamAnswerFlux(42L, "核心观点是什么？", java.util.List.of(), 5, 1024))
                .thenReturn(Flux.just("回答"));

        mockMvc.perform(post("/api/v1/posts/42/qa/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("""
                                {"question":"核心观点是什么？","history":[]}
                                """))
                .andExpect(request().asyncStarted())
                .andExpect(status().isOk());
    }
}
