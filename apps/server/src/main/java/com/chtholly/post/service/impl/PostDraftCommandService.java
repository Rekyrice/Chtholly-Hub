package com.chtholly.post.service.impl;

import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.post.id.SnowflakeIdGenerator;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.Post;
import com.chtholly.storage.StorageObjectKeyValidator;
import com.chtholly.storage.StorageService;
import com.chtholly.storage.StorageUploadValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;

/** Owns draft creation and uploaded-content confirmation commands. */
@Service
public class PostDraftCommandService {

    private static final Logger log = LoggerFactory.getLogger(PostDraftCommandService.class);

    private final PostMapper mapper;
    private final SnowflakeIdGenerator idGenerator;
    private final StorageService storageService;
    private final PostOutboxWriter outboxWriter;
    private final PostCommittedSideEffectCoordinator sideEffectCoordinator;
    private final PostMutationCacheCoordinator cacheCoordinator;

    /**
     * Creates the draft command service.
     *
     * @param mapper post persistence mapper
     * @param idGenerator post ID generator
     * @param storageService object storage boundary
     * @param outboxWriter transactional Outbox writer
     * @param sideEffectCoordinator committed side-effect boundary
     * @param cacheCoordinator mutation cache coordinator
     */
    public PostDraftCommandService(
            PostMapper mapper,
            SnowflakeIdGenerator idGenerator,
            StorageService storageService,
            PostOutboxWriter outboxWriter,
            PostCommittedSideEffectCoordinator sideEffectCoordinator,
            PostMutationCacheCoordinator cacheCoordinator) {
        this.mapper = mapper;
        this.idGenerator = idGenerator;
        this.storageService = storageService;
        this.outboxWriter = outboxWriter;
        this.sideEffectCoordinator = sideEffectCoordinator;
        this.cacheCoordinator = cacheCoordinator;
    }

    long createDraft(long creatorId) {
        long id = idGenerator.nextId();
        Instant now = Instant.now();
        mapper.insertDraft(Post.builder()
                .id(id)
                .creatorId(creatorId)
                .status("draft")
                .type("image_text")
                .visible("public")
                .isTop(false)
                .createTime(now)
                .updateTime(now)
                .build());
        return id;
    }

    /**
     * Verifies one content object without holding a database transaction or draft row lock.
     *
     * @param creatorId authenticated draft owner
     * @param postId draft post ID
     * @param objectKey immutable upload key or supported historical key
     * @param etag transport entity tag
     * @param size exact object size
     * @param sha256 exact object SHA-256 digest
     * @return immutable command accepted by the transactional binder
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PreparedContentBinding prepareContentBinding(
            long creatorId,
            long postId,
            String objectKey,
            String etag,
            Long size,
            String sha256) {
        validateContentFields(creatorId, postId, objectKey, etag, size, sha256);
        Post existing = mapper.findById(postId);
        requireOwnedDraft(existing, creatorId);
        boolean objectMatches;
        try {
            objectMatches = storageService.objectMatches(objectKey, sha256, size);
        } catch (IOException failure) {
            log.warn("Failed to verify post content object, postId={}, objectKey={}: {}",
                    postId, objectKey, failure.getMessage(), failure);
            throw new BusinessException(ErrorCode.BAD_REQUEST, "正文对象校验失败");
        }
        if (!objectMatches) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "正文对象不存在或校验失败");
        }
        String contentUrl = storageService.resolvePublicUrl(objectKey);
        if (contentUrl == null || contentUrl.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "正文访问地址非法");
        }
        cacheCoordinator.invalidateBeforeWrite(postId);
        return new PreparedContentBinding(
                creatorId,
                postId,
                objectKey,
                etag.trim(),
                size,
                sha256.toLowerCase(java.util.Locale.ROOT),
                contentUrl);
    }

    /**
     * Rechecks a prepared command under the draft row lock and binds it atomically with its Outbox row.
     *
     * @param prepared transaction-free verification result
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void bindPreparedContent(PreparedContentBinding prepared) {
        if (prepared == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "正文确认参数非法");
        }
        Post existing = mapper.findDraftByIdForUpdate(prepared.postId);
        requireOwnedDraft(existing, prepared.creatorId);
        validateContentFields(
                prepared.creatorId,
                prepared.postId,
                prepared.objectKey,
                prepared.etag,
                prepared.size,
                prepared.sha256);
        if (prepared.contentUrl == null || prepared.contentUrl.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "正文访问地址非法");
        }
        Post post = Post.builder()
                .id(prepared.postId)
                .creatorId(prepared.creatorId)
                .contentObjectKey(prepared.objectKey)
                .contentEtag(prepared.etag)
                .contentSize(prepared.size)
                .contentSha256(prepared.sha256)
                .contentUrl(prepared.contentUrl)
                .updateTime(Instant.now())
                .build();
        if (mapper.updateContent(post) == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }
        long eventId = outboxWriter.write(prepared.postId, "PostContentConfirmed", "upsert");
        cacheCoordinator.invalidatePostAfterCommit(prepared.postId);
        sideEffectCoordinator.afterContentConfirmed(eventId, prepared.postId);
    }

    private void validateContentFields(
            long creatorId,
            long postId,
            String objectKey,
            String etag,
            Long size,
            String sha256) {
        if (creatorId <= 0 || postId <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "正文确认参数非法");
        }
        StorageObjectKeyValidator.assertPostContentObjectKeyBelongsToPost(objectKey, postId);
        if (etag == null || etag.isBlank() || etag.length() > 128) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "正文 ETag 非法");
        }
        if (size == null || size <= 0 || size > StorageUploadValidator.MAX_POST_CONTENT_BYTES) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "正文大小非法");
        }
        if (sha256 == null || !sha256.matches("[0-9a-fA-F]{64}")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "正文摘要非法");
        }
    }

    private void requireOwnedDraft(Post post, long creatorId) {
        if (post == null
                || !"draft".equals(post.getStatus())
                || post.getCreatorId() == null
                || !post.getCreatorId().equals(creatorId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }
    }

    /** Immutable hand-off from object verification to the transactional binder. */
    public static final class PreparedContentBinding {

        private final long creatorId;
        private final long postId;
        private final String objectKey;
        private final String etag;
        private final long size;
        private final String sha256;
        private final String contentUrl;

        private PreparedContentBinding(
                long creatorId,
                long postId,
                String objectKey,
                String etag,
                long size,
                String sha256,
                String contentUrl) {
            this.creatorId = creatorId;
            this.postId = postId;
            this.objectKey = objectKey;
            this.etag = etag;
            this.size = size;
            this.sha256 = sha256;
            this.contentUrl = contentUrl;
        }
    }
}
