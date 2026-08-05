package com.chtholly.post.service.impl;

import com.chtholly.cache.config.CacheProperties;
import com.chtholly.cache.observability.CacheMetrics;
import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.post.mapper.PostMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicPostFeedQueryServiceCacheIsolationTest {

    @Mock private PostMapper mapper;
    @Mock private PublicPostFeedCacheGateway cacheGateway;
    @Mock private FeedItemAssembler assembler;
    @Mock private CacheMetrics cacheMetrics;

    @Test
    void offsetFragmentHitKeepsCurrentUserFlagsOutOfSharedLocalCache() {
        assertOffsetFragmentResultIsStoredWithoutUserFlags(false);
    }

    @Test
    void offsetAfterFlightHitKeepsCurrentUserFlagsOutOfSharedLocalCache() {
        assertOffsetFragmentResultIsStoredWithoutUserFlags(true);
    }

    private void assertOffsetFragmentResultIsStoredWithoutUserFlags(boolean missBeforeFlight) {
        CacheProperties properties = new CacheProperties();
        properties.setReadMode(CacheProperties.ReadMode.FULL_NO_SINGLEFLIGHT);
        PublicPostFeedQueryService service = new PublicPostFeedQueryService(
                mapper, cacheGateway, assembler, properties, cacheMetrics);

        FeedItemResponse neutralItem = item(null, null);
        FeedItemResponse personalizedItem = item(true, false);
        PublicPostFeedCacheGateway.CachedFeedPage fragments =
                new PublicPostFeedCacheGateway.CachedFeedPage(List.of(neutralItem), false, null);
        PageResponse<FeedItemResponse> personalizedPage =
                PageResponse.offset(List.of(personalizedItem), 1, 10, 0L, false, null);
        PageResponse<FeedItemResponse> neutralPage =
                PageResponse.offset(List.of(neutralItem), 1, 10, 0L, false, null);

        when(cacheGateway.pageKeyByPage(1, 10)).thenReturn("feed:public:10:1:v3");
        if (missBeforeFlight) {
            when(cacheGateway.readFragments(anyString(), anyString(), eq(10)))
                    .thenReturn(null, fragments);
        } else {
            when(cacheGateway.readFragments(anyString(), anyString(), eq(10))).thenReturn(fragments);
        }
        when(assembler.fromCached(fragments.items(), 77L)).thenReturn(List.of(personalizedItem));
        when(cacheGateway.stripUserFlags(personalizedPage)).thenReturn(neutralPage);

        PageResponse<FeedItemResponse> response =
                service.getPublicFeed(1, null, 10, null, null, 77L);

        assertThat(response).isEqualTo(personalizedPage);
        verify(cacheGateway).putLocal("feed:public:10:1:v3", neutralPage);
    }

    private static FeedItemResponse item(Boolean liked, Boolean faved) {
        return new FeedItemResponse(
                "42",
                "post-42",
                "Post 42",
                "Description",
                null,
                List.of(),
                "7",
                "owner",
                null,
                "Owner",
                "[]",
                0L,
                0L,
                0L,
                liked,
                faved,
                null,
                null);
    }
}
