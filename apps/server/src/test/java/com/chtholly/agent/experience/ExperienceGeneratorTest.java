package com.chtholly.agent.experience;

import com.chtholly.agent.cognitive.ExperienceService;
import com.chtholly.agent.mood.SeasonService;
import com.chtholly.auth.event.UserRegisteredEvent;
import com.chtholly.post.api.dto.PostDetailResponse;
import com.chtholly.post.event.PostPublishedEvent;
import com.chtholly.post.service.PostService;
import com.chtholly.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExperienceGeneratorTest {

    private ExperienceService experienceService;
    private PostService postService;
    private SeasonService seasonService;
    private ExperienceGenerator.TextGenerator textGenerator;
    private ExperienceGenerator generator;

    @BeforeEach
    void setUp() {
        experienceService = mock(ExperienceService.class);
        postService = mock(PostService.class);
        seasonService = mock(SeasonService.class);
        when(seasonService.getSeasonalThought()).thenReturn("好安静……下雪的时候，世界好像被按下了静音键。");
        textGenerator = mock(ExperienceGenerator.TextGenerator.class);
        generator = new ExperienceGenerator(experienceService, postService, textGenerator, seasonService);
    }

    @Test
    void onUserRegisteredStoresGeneratedGuestExperience() {
        when(textGenerator.generate(any())).thenReturn("有新客人来了。我会记住他的名字。");
        User user = User.builder().id(7L).nickname("rekyrice").build();

        generator.onUserRegistered(new UserRegisteredEvent(user));

        verify(experienceService).store(argThat(experience ->
                experience.text().equals("有新客人来了。我会记住他的名字。")
                        && experience.importance() == 4
                        && experience.source().equals("user-registered")));
    }

    @Test
    void onPostPublishedReadsPostTitleBeforeStoringExperience() {
        when(textGenerator.generate(any())).thenReturn("我读完了这篇文章，里面的时间很轻。");
        when(postService.getDetail(42L, null)).thenReturn(new PostDetailResponse(
                "42",
                "frieren-time",
                "时间的重量",
                "description",
                "content.md",
                List.of(),
                List.of("芙莉莲"),
                "7",
                null,
                "rekyrice",
                "[]",
                0L,
                0L,
                false,
                false,
                false,
                "public",
                "article",
                Instant.parse("2026-07-04T06:00:00Z")
        ));

        generator.onPostPublished(new PostPublishedEvent(
                42L, 7L, Instant.parse("2026-07-04T06:00:00Z"), "public"));

        verify(experienceService).store(argThat(experience ->
                experience.text().equals("我读完了这篇文章，里面的时间很轻。")
                        && experience.importance() == 3
                        && experience.source().equals("post-published")));
    }

    @Test
    void eventPromptsIncludeSeasonalThoughtContext() {
        when(textGenerator.generate(any())).thenReturn("我会把这个冬天的安静也记下来。");
        User user = User.builder().id(7L).nickname("rekyrice").build();

        generator.onUserRegistered(new UserRegisteredEvent(user));

        verify(textGenerator).generate(argThat(prompt ->
                prompt.contains("好安静……下雪的时候，世界好像被按下了静音键。")));
    }

    @Test
    void checkCommunityQuietnessStoresLonelyExperienceWhenNoRecentPosts() {
        when(postService.countSince(java.time.Duration.ofDays(3))).thenReturn(0L);

        generator.checkCommunityQuietness();

        verify(experienceService).store(argThat(experience ->
                experience.text().contains("已经 3 天没有人带新故事来了")
                        && experience.importance() == 2
                        && experience.source().equals("community-quiet")));
    }

    @Test
    void checkCommunityQuietnessDoesNothingWhenCommunityHasRecentPosts() {
        when(postService.countSince(java.time.Duration.ofDays(3))).thenReturn(2L);

        generator.checkCommunityQuietness();

        verify(experienceService, never()).store(any());
    }
}
