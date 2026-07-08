package com.chtholly.seed;

import com.chtholly.post.id.SnowflakeIdGenerator;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Persists generated seed interaction comments.
 */
@Component
public class SeedInteractionCommentWriterImpl implements SeedInteractionCommentWriter {

    private final SeedMapper mapper;
    private final SnowflakeIdGenerator idGenerator;

    public SeedInteractionCommentWriterImpl(SeedMapper mapper, SnowflakeIdGenerator idGenerator) {
        this.mapper = mapper;
        this.idGenerator = idGenerator;
    }

    @Override
    public long writeComment(long postId, Long parentCommentId, long userId, String content, Instant createdAt) {
        long commentId = idGenerator.nextId();
        mapper.insertSeedInteractionComment(new SeedInteractionCommentRow(
                commentId,
                postId,
                parentCommentId,
                userId,
                content,
                createdAt,
                createdAt));
        return commentId;
    }
}
