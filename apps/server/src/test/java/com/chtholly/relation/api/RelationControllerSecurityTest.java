package com.chtholly.relation.api;

import com.chtholly.admin.security.BannedUserFilter;
import com.chtholly.auth.config.SecurityConfig;
import com.chtholly.auth.token.JwtService;
import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.counter.service.UserCounterService;
import com.chtholly.profile.api.dto.ProfileResponse;
import com.chtholly.relation.mapper.RelationMapper;
import com.chtholly.relation.service.RelationService;
import com.chtholly.storage.config.LocalStorageWebConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security contract tests for public relation read endpoints.
 */
@WebMvcTest(
        controllers = RelationController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = LocalStorageWebConfig.class))
@Import(SecurityConfig.class)
@TestPropertySource(properties = "cors.allowed-origins=http://localhost:3000")
class RelationControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RelationService relationService;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private StringRedisTemplate redis;

    @MockBean
    private UserCounterService userCounterService;

    @MockBean
    private RelationMapper relationMapper;

    @MockBean
    private BannedUserFilter bannedUserFilter;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void given_anonymousUser_when_getFollowing_then_permitsRequest() throws Exception {
        when(relationService.followingProfilesPage(anyLong(), anyInt(), isNull(), isNull(), isNull()))
                .thenReturn(new PageResponse<ProfileResponse>(List.of(), 0, 20, 0L, false, null, false));

        mockMvc.perform(get("/api/v1/relation/following")
                        .param("userId", "1"))
                .andExpect(status().isOk());
    }

    @Test
    void given_anonymousUser_when_getFollowers_then_permitsRequest() throws Exception {
        when(relationService.followersProfilesPage(anyLong(), anyInt(), isNull(), isNull(), isNull()))
                .thenReturn(new PageResponse<ProfileResponse>(List.of(), 0, 20, 0L, false, null, false));

        mockMvc.perform(get("/api/v1/relation/followers")
                        .param("userId", "1"))
                .andExpect(status().isOk());
    }

    @Test
    void given_anonymousUser_when_getCounter_then_permitsRequest() throws Exception {
        when(redis.execute(org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.RedisCallback<byte[]>>any()))
                .thenReturn(null);

        mockMvc.perform(get("/api/v1/relation/counter")
                        .param("userId", "1"))
                .andExpect(status().isOk());
    }
}
