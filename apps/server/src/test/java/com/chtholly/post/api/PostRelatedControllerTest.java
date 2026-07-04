package com.chtholly.post.api;

import com.chtholly.agent.content.ContentUnderstandingService;
import com.chtholly.agent.content.RelatedPostDto;
import com.chtholly.auth.token.JwtService;
import com.chtholly.post.service.PostFeedService;
import com.chtholly.post.service.PostService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PostRelatedControllerTest {

    @Test
    void relatedReturnsPostsFromContentUnderstandingService() {
        PostService postService = mock(PostService.class);
        PostFeedService feedService = mock(PostFeedService.class);
        JwtService jwtService = mock(JwtService.class);
        ContentUnderstandingService contentUnderstandingService = mock(ContentUnderstandingService.class);
        when(contentUnderstandingService.getRelatedPosts(42L)).thenReturn(List.of(
                new RelatedPostDto(99L, "另一篇时间文章", "相关摘要", List.of("芙莉莲", "时间"))
        ));
        PostController controller = new PostController(postService, feedService, jwtService, contentUnderstandingService);

        List<RelatedPostDto> related = controller.related(42L);

        assertThat(related).hasSize(1);
        assertThat(related.getFirst().id()).isEqualTo(99L);
        assertThat(related.getFirst().sharedEntities()).containsExactly("芙莉莲", "时间");
    }
}
