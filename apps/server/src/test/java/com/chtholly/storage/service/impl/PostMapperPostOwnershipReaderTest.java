package com.chtholly.storage.service.impl;

import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.Post;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PostMapperPostOwnershipReaderTest {

    private final PostMapper postMapper = mock(PostMapper.class);
    private final PostMapperPostOwnershipReader reader = new PostMapperPostOwnershipReader(postMapper);

    @Test
    void isDraftOwnedBy_requiresExistingDraftWithMatchingCreator() {
        when(postMapper.findById(1L)).thenReturn(null);
        when(postMapper.findById(2L)).thenReturn(Post.builder()
                .id(2L).creatorId(null).status("draft").build());
        when(postMapper.findById(3L)).thenReturn(Post.builder()
                .id(3L).creatorId(7L).status("draft").build());
        when(postMapper.findById(4L)).thenReturn(Post.builder()
                .id(4L).creatorId(7L).status("published").build());
        when(postMapper.findById(5L)).thenReturn(Post.builder()
                .id(5L).creatorId(7L).status("deleted").build());

        assertThat(reader.isDraftOwnedBy(1L, 7L)).isFalse();
        assertThat(reader.isDraftOwnedBy(2L, 7L)).isFalse();
        assertThat(reader.isDraftOwnedBy(3L, 7L)).isTrue();
        assertThat(reader.isDraftOwnedBy(3L, 8L)).isFalse();
        assertThat(reader.isDraftOwnedBy(4L, 7L)).isFalse();
        assertThat(reader.isDraftOwnedBy(5L, 7L)).isFalse();
    }
}
