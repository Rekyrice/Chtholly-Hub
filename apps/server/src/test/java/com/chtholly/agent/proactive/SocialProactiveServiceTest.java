package com.chtholly.agent.proactive;

import com.chtholly.agent.state.CharacterStateService;
import com.chtholly.post.service.PostService;
import com.chtholly.recommendation.UserSimilarityService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SocialProactiveServiceTest {
    @Test
    void interestMatchingStopsBeforeDispatchWhenAudienceIsTooSmall() {
        UserSimilarityService similarity = mock(UserSimilarityService.class);
        PostService posts = mock(PostService.class);
        ProactiveNotificationDispatcher dispatcher = mock(ProactiveNotificationDispatcher.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        CharacterStateService state = mock(CharacterStateService.class);
        ProactiveAudienceService audience = new ProactiveAudienceService(
                state, (CharacterStateUserActivityProvider) null);
        when(state.findUserIdsActiveSince(any())).thenReturn(List.of(1L));

        new SocialProactiveService(similarity, null, null, posts, dispatcher, redis, audience, prompt -> "")
                .detectInterestMatches();

        verify(dispatcher, never()).send(anyLong(), any(), any());
    }
}
