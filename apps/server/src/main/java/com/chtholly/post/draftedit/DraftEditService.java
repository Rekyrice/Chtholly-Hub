package com.chtholly.post.draftedit;

import com.chtholly.agent.runtime.AgentLlmInvoker;
import com.chtholly.agent.skill.SkillDefinition;
import com.chtholly.agent.skill.SkillOutputValidator;
import com.chtholly.agent.skill.SkillRegistry;
import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.post.id.SnowflakeIdGenerator;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.Post;
import com.chtholly.post.service.impl.PostCacheInvalidator;
import com.chtholly.storage.StorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Synchronous preview-and-confirm boundary for the sole controlled-write Skill. */
@Service
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class DraftEditService {

    private static final String SKILL_ID = "draft-edit";
    private static final String SKILL_VERSION = "v1";
    private static final Duration PREVIEW_TTL = Duration.ofMinutes(15);
    private static final String CONTENT_TYPE = "text/markdown; charset=utf-8";

    private final PostMapper postMapper;
    private final DraftEditPreviewMapper previewMapper;
    private final SkillRegistry registry;
    private final SkillOutputValidator validator;
    private final AgentLlmInvoker llm;
    private final StorageService storage;
    private final SnowflakeIdGenerator ids;
    private final PostCacheInvalidator cacheInvalidator;
    private final TransactionOperations transactions;
    private final ObjectMapper objectMapper;

    public DraftEditService(
            PostMapper postMapper,
            DraftEditPreviewMapper previewMapper,
            SkillRegistry registry,
            SkillOutputValidator validator,
            AgentLlmInvoker llm,
            StorageService storage,
            SnowflakeIdGenerator ids,
            PostCacheInvalidator cacheInvalidator,
            TransactionOperations transactions,
            ObjectMapper objectMapper) {
        this.postMapper = postMapper;
        this.previewMapper = previewMapper;
        this.registry = registry;
        this.validator = validator;
        this.llm = llm;
        this.storage = storage;
        this.ids = ids;
        this.cacheInvalidator = cacheInvalidator;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    public PreviewResult createPreview(
            long ownerId,
            long draftId,
            String baseContent,
            String declaredBaseSha256,
            String instruction) {
        SkillDefinition definition = enabledDefinition();
        String computedBaseSha256 = sha256(baseContent);
        if (!secureEquals(computedBaseSha256, declaredBaseSha256)) {
            throw conflict("基础内容摘要不匹配");
        }
        Post draft = postMapper.findById(draftId);
        validateDraft(draft, ownerId, computedBaseSha256);

        String rawCandidate;
        try {
            rawCandidate = llm.call(
                    definition.instructionTemplate() + "\n只返回包含 candidateContent 字段的 JSON 对象。",
                    objectMapper.writeValueAsString(Map.of(
                            "draftId", String.valueOf(draftId),
                            "baseContentSha256", computedBaseSha256,
                            "instruction", instruction,
                            "draftContent", baseContent)),
                    0.1,
                    8192);
        } catch (Exception e) {
            throw internal("草稿候选生成失败", e);
        }

        String candidate = parseCandidate(rawCandidate);
        SkillOutputValidator.SkillValidationResult validation =
                validator.validateDraftContent(definition, baseContent, candidate);
        if (validation.status() != SkillOutputValidator.Status.VALID) {
            throw internal("草稿候选未通过 Skill 合同校验: " + String.join(",", validation.errors()), null);
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        DraftEditPreview preview = DraftEditPreview.builder()
                .id(ids.nextId())
                .ownerId(ownerId)
                .draftId(draftId)
                .skillId(definition.id())
                .skillVersion(definition.version())
                .baseContentSha256(computedBaseSha256)
                .candidateContent(validation.output())
                .candidateContentSha256(sha256(validation.output()))
                .status(DraftEditPreview.PENDING)
                .createdAt(now)
                .expiresAt(now.plus(PREVIEW_TTL))
                .build();
        preview.setPreviewHash(previewHash(preview));
        previewMapper.insert(preview);
        return previewResult(preview);
    }

    public DecisionResult confirm(
            long ownerId,
            long draftId,
            long previewId,
            String suppliedPreviewHash) {
        ConfirmPreflight preflight = transactions.execute(status -> confirmPreflightLocked(
                ownerId, draftId, previewId, suppliedPreviewHash));
        if (DraftEditPreview.EXPIRED.equals(preflight.status())) {
            throw conflict("草稿编辑预览已过期");
        }
        StoredCandidate stored = storeCandidate(preflight.preview());
        if (DraftEditPreview.APPLIED.equals(preflight.status())) {
            cacheInvalidator.invalidate(draftId);
            return decision(preflight.preview(), DraftEditPreview.APPLIED, stored.publicUrl());
        }
        String outcome = transactions.execute(status -> applyLocked(
                ownerId, draftId, previewId, suppliedPreviewHash, stored));
        if (DraftEditPreview.APPLIED.equals(outcome)) {
            cacheInvalidator.invalidate(draftId);
            return decision(preflight.preview(), outcome, stored.publicUrl());
        }
        if (DraftEditPreview.EXPIRED.equals(outcome)) {
            throw conflict("草稿编辑预览已过期");
        }
        throw conflict("草稿内容已更新，请重新生成预览");
    }

    public DecisionResult reject(
            long ownerId,
            long draftId,
            long previewId,
            String suppliedPreviewHash) {
        String outcome = transactions.execute(status -> {
            DraftEditPreview current = requireOwned(
                    previewMapper.findByIdForUpdate(previewId), ownerId, draftId, suppliedPreviewHash);
            if (DraftEditPreview.REJECTED.equals(current.getStatus())) {
                return DraftEditPreview.REJECTED;
            }
            requirePending(current);
            Instant now = Instant.now();
            if (expired(current, now)) {
                previewMapper.markExpired(previewId, now);
                return DraftEditPreview.EXPIRED;
            }
            if (previewMapper.markRejected(previewId, now) != 1) {
                throw internal("草稿编辑预览状态更新失败", null);
            }
            return DraftEditPreview.REJECTED;
        });
        if (DraftEditPreview.EXPIRED.equals(outcome)) {
            throw conflict("草稿编辑预览已过期");
        }
        return new DecisionResult(String.valueOf(previewId), String.valueOf(draftId), outcome, null, null);
    }

    private String applyLocked(
            long ownerId,
            long draftId,
            long previewId,
            String suppliedPreviewHash,
            StoredCandidate stored) {
        DraftEditPreview current = requireOwned(
                previewMapper.findByIdForUpdate(previewId), ownerId, draftId, suppliedPreviewHash);
        if (DraftEditPreview.APPLIED.equals(current.getStatus())) {
            return DraftEditPreview.APPLIED;
        }
        requirePending(current);
        Instant now = Instant.now();
        if (expired(current, now)) {
            if (previewMapper.markExpired(previewId, now) != 1) {
                throw internal("草稿编辑预览过期状态不一致", null);
            }
            return DraftEditPreview.EXPIRED;
        }
        Post draft = postMapper.findDraftByIdForUpdate(draftId);
        if (draft == null
                || !Long.valueOf(ownerId).equals(draft.getCreatorId())
                || !secureEquals(current.getBaseContentSha256(), draft.getContentSha256())) {
            return "CONFLICT";
        }
        Post update = Post.builder()
                .id(draftId)
                .creatorId(ownerId)
                .contentObjectKey(stored.objectKey())
                .contentUrl(stored.publicUrl())
                .contentEtag(null)
                .contentSize(stored.size())
                .contentSha256(current.getCandidateContentSha256())
                .updateTime(now)
                .build();
        if (postMapper.applyDraftEdit(update, current.getBaseContentSha256()) != 1) {
            return "CONFLICT";
        }
        if (previewMapper.markApplied(previewId, now) != 1) {
            throw internal("草稿编辑预览提交状态不一致", null);
        }
        return DraftEditPreview.APPLIED;
    }

    private StoredCandidate storeCandidate(DraftEditPreview preview) {
        byte[] bytes = preview.getCandidateContent().getBytes(StandardCharsets.UTF_8);
        String objectKey = objectKey(preview);
        try {
            if (storage.objectExists(objectKey)) {
                if (!storage.objectMatches(objectKey, preview.getCandidateContentSha256(), bytes.length)) {
                    throw internal("草稿编辑对象完整性冲突", null);
                }
            }
            // Reassert the verified bytes and storage access policy after partial OSS failures.
            storage.uploadVerifiedObject(
                    objectKey,
                    new ByteArrayInputStream(bytes),
                    CONTENT_TYPE,
                    bytes.length,
                    preview.getCandidateContentSha256());
            return new StoredCandidate(objectKey, storage.resolvePublicUrl(objectKey), bytes.length);
        } catch (IOException e) {
            throw internal("草稿编辑对象写入失败", e);
        }
    }

    private SkillDefinition enabledDefinition() {
        return registry.enabled().stream()
                .filter(definition -> SKILL_ID.equals(definition.id()) && SKILL_VERSION.equals(definition.version()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "draft-edit@v1 未启用", HttpStatus.NOT_FOUND.value()));
    }

    private void validateDraft(Post draft, long ownerId, String baseSha256) {
        if (draft == null) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND, "草稿不存在", HttpStatus.NOT_FOUND.value());
        }
        if (!Long.valueOf(ownerId).equals(draft.getCreatorId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权编辑该草稿", HttpStatus.FORBIDDEN.value());
        }
        if (!"draft".equals(draft.getStatus()) || !secureEquals(baseSha256, draft.getContentSha256())) {
            throw conflict("草稿基础版本已变化");
        }
    }

    private DraftEditPreview requireOwned(
            DraftEditPreview preview,
            long ownerId,
            long draftId,
            String suppliedPreviewHash) {
        if (preview == null) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND, "草稿编辑预览不存在", HttpStatus.NOT_FOUND.value());
        }
        if (!Long.valueOf(ownerId).equals(preview.getOwnerId())
                || !Long.valueOf(draftId).equals(preview.getDraftId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权操作该预览", HttpStatus.FORBIDDEN.value());
        }
        if (!persistedIntegrityValid(preview)
                || !secureEquals(preview.getPreviewHash(), suppliedPreviewHash)) {
            throw conflict("草稿编辑预览摘要不匹配");
        }
        return preview;
    }

    private void requirePending(DraftEditPreview preview) {
        if (!DraftEditPreview.PENDING.equals(preview.getStatus())) {
            throw conflict("草稿编辑预览已结束: " + preview.getStatus());
        }
    }

    private ConfirmPreflight confirmPreflightLocked(
            long ownerId,
            long draftId,
            long previewId,
            String suppliedPreviewHash) {
        DraftEditPreview current = requireOwned(
                previewMapper.findByIdForUpdate(previewId), ownerId, draftId, suppliedPreviewHash);
        if (DraftEditPreview.APPLIED.equals(current.getStatus())) {
            return new ConfirmPreflight(current, DraftEditPreview.APPLIED);
        }
        requirePending(current);
        Instant now = Instant.now();
        if (expired(current, now)) {
            if (previewMapper.markExpired(previewId, now) != 1) {
                throw internal("草稿编辑预览过期状态不一致", null);
            }
            return new ConfirmPreflight(current, DraftEditPreview.EXPIRED);
        }
        return new ConfirmPreflight(current, DraftEditPreview.PENDING);
    }

    private boolean persistedIntegrityValid(DraftEditPreview preview) {
        try {
            return secureEquals(preview.getCandidateContentSha256(), sha256(preview.getCandidateContent()))
                    && secureEquals(preview.getPreviewHash(), previewHash(preview));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private String parseCandidate(String raw) {
        try {
            String value = raw == null ? "" : raw.strip();
            int start = value.indexOf('{');
            int end = value.lastIndexOf('}');
            if (start < 0 || end < start) {
                throw new IllegalArgumentException("missing JSON object");
            }
            JsonNode node = objectMapper.readTree(value.substring(start, end + 1));
            JsonNode content = node.get("candidateContent");
            if (content == null || !content.isTextual()) {
                throw new IllegalArgumentException("missing candidateContent");
            }
            return content.textValue();
        } catch (Exception e) {
            throw internal("草稿候选不是有效的结构化输出", e);
        }
    }

    private static boolean expired(DraftEditPreview preview, Instant now) {
        return preview.getExpiresAt() == null || !preview.getExpiresAt().isAfter(now);
    }

    private static String objectKey(DraftEditPreview preview) {
        return "posts/" + preview.getDraftId() + "/content-edits/"
                + preview.getCandidateContentSha256() + ".md";
    }

    static String previewHash(DraftEditPreview preview) {
        return sha256(String.join("\n", List.of(
                String.valueOf(preview.getId()),
                String.valueOf(preview.getOwnerId()),
                String.valueOf(preview.getDraftId()),
                preview.getSkillId(),
                preview.getSkillVersion(),
                preview.getBaseContentSha256(),
                preview.getCandidateContentSha256(),
                String.valueOf(preview.getExpiresAt().toEpochMilli()))));
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static boolean secureEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    private PreviewResult previewResult(DraftEditPreview preview) {
        return new PreviewResult(
                String.valueOf(preview.getId()),
                String.valueOf(preview.getDraftId()),
                preview.getSkillId(),
                preview.getSkillVersion(),
                preview.getBaseContentSha256(),
                preview.getCandidateContentSha256(),
                preview.getPreviewHash(),
                preview.getCandidateContent(),
                preview.getStatus(),
                preview.getExpiresAt());
    }

    private DecisionResult decision(DraftEditPreview preview, String status, String publicUrl) {
        return new DecisionResult(
                String.valueOf(preview.getId()),
                String.valueOf(preview.getDraftId()),
                status,
                preview.getCandidateContentSha256(),
                publicUrl);
    }

    private BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message, HttpStatus.CONFLICT.value());
    }

    private BusinessException internal(String message, Exception cause) {
        BusinessException exception = new BusinessException(
                ErrorCode.INTERNAL_ERROR, message, HttpStatus.INTERNAL_SERVER_ERROR.value());
        if (cause != null) {
            exception.initCause(cause);
        }
        return exception;
    }

    public record PreviewResult(
            String previewId,
            String draftId,
            String skillId,
            String skillVersion,
            String baseContentSha256,
            String candidateContentSha256,
            String previewHash,
            String candidateContent,
            String status,
            Instant expiresAt) {
    }

    public record DecisionResult(
            String previewId,
            String draftId,
            String status,
            String contentSha256,
            String contentUrl) {
    }

    private record StoredCandidate(String objectKey, String publicUrl, long size) {
    }

    private record ConfirmPreflight(DraftEditPreview preview, String status) {
    }
}
