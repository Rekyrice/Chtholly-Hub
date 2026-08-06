package com.chtholly.comment.api;

import com.chtholly.admin.security.BannedUserFilter;
import com.chtholly.auth.config.SecurityConfig;
import com.chtholly.auth.token.JwtService;
import com.chtholly.comment.api.dto.UserCommentActivityResponse;
import com.chtholly.comment.service.CommentService;
import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.storage.config.LocalStorageWebConfig;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security and validation contract tests for public user comment activity.
 */
@WebMvcTest(
        controllers = UserCommentController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = LocalStorageWebConfig.class))
@Import(SecurityConfig.class)
@TestPropertySource(properties = "cors.allowed-origins=http://localhost:3000")
class UserCommentControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CommentService commentService;

    @MockBean
    private JwtService jwtService;

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
    void givenAnonymousUserWhenGetCommentsThenPermitsRequest() throws Exception {
        when(commentService.listByUser(9L, 1, 20))
                .thenReturn(PageResponse.offset(List.<UserCommentActivityResponse>of(), 1, 20, 0L));

        mockMvc.perform(get("/api/v1/users/9/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void givenZeroPageWhenGetCommentsThenReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/users/9/comments").param("page", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void givenOversizedPageWhenGetCommentsThenReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/users/9/comments").param("size", "51"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void givenAnonymousUserWhenPostCommentsThenRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/users/9/comments"))
                .andExpect(status().isUnauthorized());
    }
}
