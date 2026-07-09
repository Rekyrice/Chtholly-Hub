package com.chtholly.search.api;

import com.chtholly.admin.security.BannedUserFilter;
import com.chtholly.auth.config.SecurityConfig;
import com.chtholly.auth.token.JwtService;
import com.chtholly.recommendation.UserInterestProfile;
import com.chtholly.search.api.dto.HubFeedResponse;
import com.chtholly.search.service.SearchService;
import com.chtholly.storage.config.LocalStorageWebConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.FilterType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security contract tests for public search endpoints.
 */
@WebMvcTest(
        controllers = SearchController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = LocalStorageWebConfig.class))
@Import(SecurityConfig.class)
@TestPropertySource(properties = "cors.allowed-origins=http://localhost:3000")
class SearchControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SearchService searchService;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private UserInterestProfile userInterestProfile;

    @MockBean
    private BannedUserFilter bannedUserFilter;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void given_anonymousUser_when_getHubFeed_then_permitsRequest() throws Exception {
        when(searchService.hubFeed(isNull(), isNull(), eq(1), eq(8)))
                .thenReturn(new HubFeedResponse(
                        List.of(), "ok", 0,
                        List.of(), "ok",
                        List.of(), "ok",
                        List.of(), "ok"));

        mockMvc.perform(get("/api/v1/search/hub-feed"))
                .andExpect(status().isOk());
    }

    @Test
    void given_pageParams_when_getHubFeed_then_passesPagingToService() throws Exception {
        when(searchService.hubFeed(isNull(), isNull(), eq(2), eq(12)))
                .thenReturn(new HubFeedResponse(
                        List.of(), "ok", 0,
                        List.of(), "ok",
                        List.of(), "ok",
                        List.of(), "ok"));

        mockMvc.perform(get("/api/v1/search/hub-feed?page=2&size=12"))
                .andExpect(status().isOk());
    }
}
