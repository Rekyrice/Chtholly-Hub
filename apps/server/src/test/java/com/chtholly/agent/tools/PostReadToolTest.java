package com.chtholly.agent.tools;

import com.chtholly.agent.search.SearchResult;
import com.chtholly.llm.rag.RagQueryService;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.PostDetailRow;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostReadToolTest {

    @Test
    void overviewReadsRepresentativeChunksFromTheExactPublicPost() {
        PostMapper postMapper = mock(PostMapper.class);
        RagQueryService ragQueryService = mock(RagQueryService.class);
        when(postMapper.findDetailBySlug("apocalypse-hotel-legacies"))
                .thenReturn(publicPost());
        when(ragQueryService.searchPost(42L, "文章标题 文章摘要", 4))
                .thenReturn(List.of(chunk("比世界活得更久的酒店，留下了人的生活痕迹。")));
        PostReadTool tool = new PostReadTool(postMapper, ragQueryService);

        String result = tool.execute(Map.of(
                "slug", "apocalypse-hotel-legacies",
                "mode", "overview"), 7L);

        verify(ragQueryService).searchPost(42L, "文章标题 文章摘要", 4);
        assertThat(result).contains(
                "STATUS: SUCCESS",
                "《文章标题》",
                "/post/apocalypse-hotel-legacies",
                "相关正文片段",
                "比世界活得更久的酒店");
    }

    @Test
    void focusedReadUsesTheUsersSpecificQuestion() {
        PostMapper postMapper = mock(PostMapper.class);
        RagQueryService ragQueryService = mock(RagQueryService.class);
        when(postMapper.findDetailBySlug("apocalypse-hotel-legacies"))
                .thenReturn(publicPost());
        when(ragQueryService.searchPost(42L, "作者为什么认为酒店比世界活得更久", 2))
                .thenReturn(List.of(chunk("酒店保存了已经消失的人类习惯。")));
        PostReadTool tool = new PostReadTool(postMapper, ragQueryService);

        String result = tool.execute(Map.of(
                "slug", "/post/apocalypse-hotel-legacies",
                "mode", "focused",
                "query", "作者为什么认为酒店比世界活得更久",
                "topK", 2), 7L);

        verify(ragQueryService).searchPost(42L, "作者为什么认为酒店比世界活得更久", 2);
        assertThat(result).contains("STATUS: SUCCESS", "酒店保存了已经消失的人类习惯");
    }

    @Test
    void overviewBoundsTheGeneratedRetrievalQuery() {
        PostMapper postMapper = mock(PostMapper.class);
        PostDetailRow post = publicPost();
        post.setDescription("摘要".repeat(200));
        when(postMapper.findDetailBySlug("apocalypse-hotel-legacies")).thenReturn(post);
        RagQueryService ragQueryService = mock(RagQueryService.class);
        when(ragQueryService.searchPost(eq(42L), anyString(), eq(4)))
                .thenReturn(List.of(chunk("正文片段")));

        new PostReadTool(postMapper, ragQueryService)
                .execute(Map.of("slug", "apocalypse-hotel-legacies"), 7L);

        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(ragQueryService).searchPost(eq(42L), query.capture(), eq(4));
        assertThat(query.getValue()).startsWith("文章标题").hasSizeLessThanOrEqualTo(200);
    }

    @Test
    void missingPrivateAndDraftPostsShareTheSameNonDisclosingStatus() {
        PostMapper missingMapper = mock(PostMapper.class);
        PostReadTool missingTool = new PostReadTool(missingMapper, mock(RagQueryService.class));
        assertThat(missingTool.execute(Map.of("slug", "missing"), 7L))
                .startsWith("STATUS: NOT_ACCESSIBLE");

        PostMapper privateMapper = mock(PostMapper.class);
        PostDetailRow privatePost = publicPost();
        privatePost.setVisible("private");
        when(privateMapper.findDetailBySlug("private-post")).thenReturn(privatePost);
        RagQueryService privateRag = mock(RagQueryService.class);
        PostReadTool privateTool = new PostReadTool(privateMapper, privateRag);
        assertThat(privateTool.execute(Map.of("slug", "private-post"), 7L))
                .startsWith("STATUS: NOT_ACCESSIBLE");
        verify(privateRag, never()).searchPost(42L, "文章标题 文章摘要", 4);

        PostMapper draftMapper = mock(PostMapper.class);
        PostDetailRow draftPost = publicPost();
        draftPost.setStatus("draft");
        when(draftMapper.findDetailBySlug("draft-post")).thenReturn(draftPost);
        assertThat(new PostReadTool(draftMapper, mock(RagQueryService.class))
                .execute(Map.of("slug", "draft-post"), 7L))
                .startsWith("STATUS: NOT_ACCESSIBLE");
    }

    @Test
    void postWithoutReadableContentReturnsContentUnavailable() {
        PostMapper postMapper = mock(PostMapper.class);
        PostDetailRow row = publicPost();
        row.setContentUrl(" ");
        when(postMapper.findDetailBySlug("no-content")).thenReturn(row);
        RagQueryService ragQueryService = mock(RagQueryService.class);

        String result = new PostReadTool(postMapper, ragQueryService)
                .execute(Map.of("slug", "no-content"), 7L);

        assertThat(result).startsWith("STATUS: CONTENT_UNAVAILABLE");
        verify(ragQueryService, never()).searchPost(42L, "文章标题 文章摘要", 4);
    }

    @Test
    void emptyScopedSearchDoesNotClaimThePostWasMovedOrMadePrivate() {
        PostMapper postMapper = mock(PostMapper.class);
        RagQueryService ragQueryService = mock(RagQueryService.class);
        when(postMapper.findDetailBySlug("apocalypse-hotel-legacies"))
                .thenReturn(publicPost());
        when(ragQueryService.searchPost(42L, "文章标题 文章摘要", 4)).thenReturn(List.of());

        String result = new PostReadTool(postMapper, ragQueryService)
                .execute(Map.of("slug", "apocalypse-hotel-legacies"), 7L);

        assertThat(result)
                .startsWith("STATUS: NO_RELEVANT_SNIPPETS")
                .doesNotContain("移动", "私密", "删除");
    }

    @Test
    void indexFailureReturnsStableStatusWithoutLeakingInternalMessage() {
        PostMapper postMapper = mock(PostMapper.class);
        RagQueryService ragQueryService = mock(RagQueryService.class);
        when(postMapper.findDetailBySlug("apocalypse-hotel-legacies"))
                .thenReturn(publicPost());
        when(ragQueryService.searchPost(42L, "文章标题 文章摘要", 4))
                .thenThrow(new IllegalStateException("secret vector endpoint failed"));

        String result = new PostReadTool(postMapper, ragQueryService)
                .execute(Map.of("slug", "apocalypse-hotel-legacies"), 7L);

        assertThat(result)
                .startsWith("STATUS: INDEX_FAILED")
                .doesNotContain("secret vector endpoint failed");
    }

    private PostDetailRow publicPost() {
        PostDetailRow row = new PostDetailRow();
        row.setId(42L);
        row.setTitle("文章标题");
        row.setSlug("apocalypse-hotel-legacies");
        row.setDescription("文章摘要");
        row.setContentUrl("https://content.example/post.md");
        row.setContentSha256("sha-42");
        row.setStatus("published");
        row.setVisible("public");
        return row;
    }

    private SearchResult chunk(String snippet) {
        return new SearchResult(
                "post:42",
                "文章标题",
                snippet,
                "semantic",
                0.9,
                "post:42",
                "post:42:sha-42:0",
                "current",
                "sha-42",
                Set.of("PUBLIC"));
    }
}
