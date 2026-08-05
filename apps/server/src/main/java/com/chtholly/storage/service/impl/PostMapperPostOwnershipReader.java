package com.chtholly.storage.service.impl;

import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.Post;
import com.chtholly.storage.service.PostOwnershipReader;
import org.springframework.stereotype.Component;

/**
 * MyBatis adapter for the storage module's narrow post ownership read port.
 */
@Component
public class PostMapperPostOwnershipReader implements PostOwnershipReader {

    private final PostMapper postMapper;

    /**
     * Creates the ownership adapter.
     *
     * @param postMapper persisted post reader
     */
    public PostMapperPostOwnershipReader(PostMapper postMapper) {
        this.postMapper = postMapper;
    }

    /**
     * Checks draft ownership from the authoritative post row.
     *
     * @param postId post ID
     * @param userId expected owner ID
     * @return whether the post is still the user's draft
     */
    @Override
    public boolean isDraftOwnedBy(long postId, long userId) {
        Post post = postMapper.findById(postId);
        return post != null
                && "draft".equals(post.getStatus())
                && post.getCreatorId() != null
                && post.getCreatorId() == userId;
    }
}
