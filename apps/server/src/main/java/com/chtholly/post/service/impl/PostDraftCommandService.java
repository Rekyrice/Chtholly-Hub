package com.chtholly.post.service.impl;

import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.post.id.SnowflakeIdGenerator;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.Post;
import com.chtholly.storage.config.OssProperties;
import org.springframework.stereotype.Service;

import java.time.Instant;

/** Owns draft creation and uploaded-content confirmation commands. */
@Service
public class PostDraftCommandService {

    private final PostMapper mapper;
    private final SnowflakeIdGenerator idGenerator;
    private final OssProperties ossProperties;
    private final PostMutationCacheCoordinator cacheCoordinator;
    private final PostSearchCoordinator searchCoordinator;

    /**
     * Creates the draft command service.
     *
     * @param mapper post persistence mapper
     * @param idGenerator post ID generator
     * @param ossProperties object storage URL properties
     * @param cacheCoordinator mutation cache coordinator
     * @param searchCoordinator best-effort search coordinator
     */
    public PostDraftCommandService(
            PostMapper mapper,
            SnowflakeIdGenerator idGenerator,
            OssProperties ossProperties,
            PostMutationCacheCoordinator cacheCoordinator,
            PostSearchCoordinator searchCoordinator) {
        this.mapper = mapper;
        this.idGenerator = idGenerator;
        this.ossProperties = ossProperties;
        this.cacheCoordinator = cacheCoordinator;
        this.searchCoordinator = searchCoordinator;
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

    void confirmContent(
            long creatorId,
            long postId,
            String objectKey,
            String etag,
            Long size,
            String sha256) {
        cacheCoordinator.invalidateBeforeWrite(postId);
        Post post = Post.builder()
                .id(postId)
                .creatorId(creatorId)
                .contentObjectKey(objectKey)
                .contentEtag(etag)
                .contentSize(size)
                .contentSha256(sha256)
                .contentUrl(publicUrl(objectKey))
                .updateTime(Instant.now())
                .build();
        if (mapper.updateContent(post) == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }
        cacheCoordinator.invalidatePostAfterCommit(postId);
        searchCoordinator.preIndexAfterContentConfirm(postId);
    }

    private String publicUrl(String objectKey) {
        String publicDomain = ossProperties.getPublicDomain();
        if (publicDomain != null && !publicDomain.isBlank()) {
            return publicDomain.replaceAll("/$", "") + "/" + objectKey;
        }
        return "https://" + ossProperties.getBucket() + "." + ossProperties.getEndpoint() + "/" + objectKey;
    }
}
