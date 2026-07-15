package com.chtholly.seed.contentpack;

import com.chtholly.config.SiteProperties;
import com.chtholly.post.id.SnowflakeIdGenerator;
import com.chtholly.seed.contentpack.ContentPackMapper.FollowState;
import com.chtholly.seed.contentpack.ContentPackMapper.PostState;
import com.chtholly.seed.contentpack.ContentPackMapper.RetirementCandidate;
import com.chtholly.seed.contentpack.ContentPackMapper.FollowingState;
import com.chtholly.seed.contentpack.ContentPackMapper.FollowerState;
import com.chtholly.seed.contentpack.ContentPackMediaPublisher.PublishedAsset;
import com.chtholly.seed.contentpack.ContentPackMediaPublisher.PublishedContent;
import com.chtholly.seed.contentpack.model.ContentPack;
import com.chtholly.seed.contentpack.model.SeedAccountDefinition;
import com.chtholly.seed.contentpack.model.SeedCommentDefinition;
import com.chtholly.seed.contentpack.model.SeedContentIdentity;
import com.chtholly.seed.contentpack.model.SeedFollowDefinition;
import com.chtholly.seed.contentpack.model.SeedPostDefinition;
import com.chtholly.tag.service.TagService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Applies one validated content pack to MySQL while preserving stable Seed entity IDs.
 *
 * <p>This class owns only relational mutations. Storage lease decisions, cache invalidation,
 * counters and search indexing belong to the import orchestrator after this transaction commits.
 */
@Component
public class ContentPackDatabaseWriter {

    private static final String ACCOUNT = "ACCOUNT";
    private static final String POST = "POST";
    private static final String COMMENT = "COMMENT";
    private static final String FOLLOW = "FOLLOW";
    private static final String SEED_EMAIL_SUFFIX = "@seed.chtholly.invalid";

    private final ContentPackMapper mapper;
    private final SnowflakeIdGenerator idGenerator;
    private final TagService tagService;
    private final ObjectMapper objectMapper;
    private final SiteProperties siteProperties;

    /**
     * Creates the transactional content-pack database boundary.
     *
     * @param mapper focused Seed persistence mapper
     * @param idGenerator stable ID generator for new entities
     * @param tagService published tag usage reconciler
     * @param objectMapper JSON serializer for database columns and identity metadata
     * @param siteProperties site-level identities used by reserved content-pack authors
     */
    public ContentPackDatabaseWriter(
            ContentPackMapper mapper,
            SnowflakeIdGenerator idGenerator,
            TagService tagService,
            ObjectMapper objectMapper,
            SiteProperties siteProperties) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.tagService = Objects.requireNonNull(tagService, "tagService");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.siteProperties = Objects.requireNonNull(siteProperties, "siteProperties");
    }

    /**
     * Writes accounts, posts, comments and Seed-to-Seed follows atomically.
     *
     * @param pack validated source-of-truth pack
     * @param published already-published immutable media and Markdown URLs
     * @return stable identities and exact post mutation counts for post-commit work
     */
    @Transactional
    public WriteResult write(ContentPack pack, PublishedContent published) {
        Objects.requireNonNull(pack, "pack");
        Objects.requireNonNull(published, "published");
        requireValidReservedIdentities(pack);
        ContentPackValidator.requireValidCommentGraph(pack.comments());
        String namespace = requireText(pack.manifest().namespace(), "namespace");
        String version = requireText(pack.manifest().version(), "pack version");
        if (!namespace.equals(published.namespace())) {
            throw new IllegalArgumentException("published namespace does not match content pack");
        }

        Map<String, Long> externalPostIdsBySlug = resolveExternalPostIds(pack);

        ContentPackIdentityResolver resolver = new ContentPackIdentityResolver(namespace, mapper, idGenerator);
        Map<String, Long> accountIds = writeAccounts(pack, published, namespace, version, resolver);
        PostWriteState posts = writePosts(pack, published, accountIds, namespace, version, resolver);
        RetirementWriteState retirements = retirePosts(pack, Set.copyOf(posts.postIds().values()));
        writeComments(pack, accountIds, posts.postIds(), externalPostIdsBySlug, namespace, version);
        writeFollows(pack, accountIds, namespace, version);
        return new WriteResult(
                new ResolvedIdentities(namespace, accountIds, posts.postIds(), externalPostIdsBySlug),
                posts.changedPostIds(),
                posts.createdPostCountsByAuthor(),
                retirements.retiredPostIds(),
                retirements.retiredAuthorIds(),
                retirements.unmatchedSlugs());
    }

    private void requireValidReservedIdentities(ContentPack pack) {
        for (SeedAccountDefinition account : pack.accounts()) {
            if (ContentPackValidator.SITE_OWNER_AUTHOR.equals(account.seedKey())) {
                // 写库边界必须独立防御，避免绕过校验器时把真实站长别名落成 SeedUser。
                throw new IllegalArgumentException(
                        "reserved account seedKey: " + ContentPackValidator.SITE_OWNER_AUTHOR);
            }
        }
        boolean usesSiteOwner = pack.posts().stream()
                .anyMatch(post -> ContentPackValidator.SITE_OWNER_AUTHOR.equals(post.authorSeedKey()));
        if (usesSiteOwner) {
            requireSiteOwnerUserId();
        }
    }

    private Map<String, Long> writeAccounts(
            ContentPack pack,
            PublishedContent published,
            String namespace,
            String version,
            ContentPackIdentityResolver resolver) {
        Map<String, Long> accountIds = new LinkedHashMap<>();
        for (SeedAccountDefinition account : pack.accounts()) {
            long id = resolver.resolveAccountId(account, version);
            PublishedAsset avatar = requireAsset(published, account.avatarAsset(), "avatar for " + account.seedKey());
            Instant updatedAt = Instant.now();
            Instant createdAt = account.joinedAt() == null ? updatedAt : account.joinedAt();
            SeedUserRow row = new SeedUserRow(
                    id, seedEmail(account), account.nickname(), avatar.publicUrl(), account.bio(), account.handle(),
                    account.gender(), account.birthday(), account.school(), json(account.tags()), createdAt, updatedAt);
            if (mapper.seedUserExistsById(id)) {
                mapper.updateSeedUserById(row);
            } else {
                mapper.insertSeedUser(row);
            }
            Map<String, Object> publicState = new LinkedHashMap<>();
            publicState.put("nickname", account.nickname());
            publicState.put("handle", account.handle());
            publicState.put("bio", account.bio());
            publicState.put("avatar", avatar.sha256());
            publicState.put("tags", account.tags());
            updateIdentityHash(namespace, ACCOUNT, account.seedKey(), version, sha256(json(publicState)), "{}");
            accountIds.put(account.seedKey(), id);
        }
        return accountIds;
    }

    private PostWriteState writePosts(
            ContentPack pack,
            PublishedContent published,
            Map<String, Long> accountIds,
            String namespace,
            String version,
            ContentPackIdentityResolver resolver) {
        Map<String, Long> postIds = new LinkedHashMap<>();
        List<Long> changedPostIds = new ArrayList<>();
        Map<Long, Integer> createdCounts = new LinkedHashMap<>();
        for (SeedPostDefinition post : pack.posts()) {
            long creatorId = resolvePostAuthorId(accountIds, post.authorSeedKey());
            SeedContentIdentity before = mapper.findIdentity(namespace, POST, post.seedKey());
            long postId = resolver.resolvePostId(post, version);
            PostState existing = mapper.findPostStateById(postId);
            String hash = contentHash(post, creatorId, published);
            postIds.put(post.seedKey(), postId);
            if (existing != null && before != null && hash.equals(before.contentHash())) {
                updateIdentityHash(namespace, POST, post.seedKey(), version, hash,
                        json(Map.of("contentObjectKey", nullToEmpty(existing.contentObjectKey()))));
                continue;
            }

            PublishedAsset markdown = requireMarkdown(published, post.seedKey());
            SeedPostRow row = new SeedPostRow(
                    postId, creatorId, post.title(), post.slug(), post.description(), markdown.publicUrl(),
                    markdown.objectKey(), markdown.sha256(), markdown.size(), json(post.tags()),
                    json(imageUrls(post, published)), post.publishTime().minus(2, ChronoUnit.HOURS),
                    post.publishTime());
            List<String> oldTags = existing == null ? List.of() : parseTags(existing.tagsJson());
            if (existing == null) {
                mapper.insertSeedPost(row);
                createdCounts.merge(creatorId, 1, Integer::sum);
            } else {
                mapper.updateSeedPostById(row);
            }
            tagService.syncPublishedPostTags(creatorId, oldTags, post.tags());
            updateIdentityHash(namespace, POST, post.seedKey(), version, hash,
                    json(Map.of("contentObjectKey", markdown.objectKey())));
            changedPostIds.add(postId);
        }
        return new PostWriteState(postIds, changedPostIds, createdCounts);
    }

    private RetirementWriteState retirePosts(ContentPack pack, Set<Long> contentPackPostIds) {
        if (pack.retirements().isEmpty()) {
            return new RetirementWriteState(List.of(), List.of(), List.of());
        }
        long ownerUserId = requireSiteOwnerUserId();
        List<String> declaredSlugs = pack.retirements().stream().map(retirement -> retirement.slug()).toList();
        List<RetirementCandidate> candidates = mapper.findRetirementCandidates(declaredSlugs);
        Map<String, RetirementCandidate> candidatesBySlug = new LinkedHashMap<>();
        for (RetirementCandidate candidate : candidates) {
            if (candidate.creatorId() == ownerUserId) {
                throw new IllegalStateException("retirement allowlist matched site owner post: " + candidate.slug());
            }
            if (contentPackPostIds.contains(candidate.id())) {
                throw new IllegalStateException("retirement allowlist matched content-pack post: " + candidate.slug());
            }
            if (!"published".equals(candidate.status()) && !"deleted".equals(candidate.status())) {
                throw new IllegalStateException(
                        "retirement candidate has unsupported status: " + candidate.slug() + " -> " + candidate.status());
            }
            String folded = foldSlug(candidate.slug());
            if (candidatesBySlug.putIfAbsent(folded, candidate) != null) {
                throw new IllegalStateException("multiple retirement candidates matched slug: " + candidate.slug());
            }
        }

        List<Long> retiredPostIds = new ArrayList<>();
        Set<Long> retiredAuthorIds = new LinkedHashSet<>();
        List<String> unmatchedSlugs = new ArrayList<>();
        for (String declaredSlug : declaredSlugs) {
            RetirementCandidate candidate = candidatesBySlug.get(foldSlug(declaredSlug));
            if (candidate == null) {
                unmatchedSlugs.add(declaredSlug);
                continue;
            }
            if ("deleted".equals(candidate.status())) {
                continue;
            }
            int affected = mapper.softDeleteRetirementPost(candidate.id());
            if (affected != 1) {
                throw new IllegalStateException(
                        "expected to retire exactly one post: " + candidate.slug() + ", affected: " + affected);
            }
            tagService.releasePublishedPostTags(parseTags(candidate.tagsJson()));
            retiredPostIds.add(candidate.id());
            retiredAuthorIds.add(candidate.creatorId());
        }
        return new RetirementWriteState(
                List.copyOf(retiredPostIds), List.copyOf(retiredAuthorIds), List.copyOf(unmatchedSlugs));
    }

    private String foldSlug(String slug) {
        return slug.toLowerCase(java.util.Locale.ROOT);
    }

    private long resolvePostAuthorId(Map<String, Long> accountIds, String authorSeedKey) {
        if (!ContentPackValidator.SITE_OWNER_AUTHOR.equals(authorSeedKey)) {
            return requireIdentity(accountIds, authorSeedKey, "post author");
        }
        return requireSiteOwnerUserId();
    }

    private long requireSiteOwnerUserId() {
        long ownerUserId = siteProperties.ownerUserId();
        if (ownerUserId <= 0) {
            // 保留作者必须映射到已配置的真实站长，禁止悄悄生成 Seed 账号或无效外键。
            throw new IllegalStateException("site.ownerUserId must be positive for site-owner post author");
        }
        return ownerUserId;
    }

    private void writeComments(
            ContentPack pack,
            Map<String, Long> accountIds,
            Map<String, Long> postIds,
            Map<String, Long> externalPostIdsBySlug,
            String namespace,
            String version) {
        Map<String, Long> commentIds = new LinkedHashMap<>();
        for (SeedCommentDefinition comment : pack.comments()) {
            SeedContentIdentity existing = mapper.findIdentity(namespace, COMMENT, comment.seedKey());
            long postId = resolveInteractionPostId(
                    comment.postSeedKey(), comment.postSlug(), postIds, externalPostIdsBySlug, "comment post");
            long authorId = requireIdentity(accountIds, comment.authorSeedKey(), "comment author");
            long commentId;
            if (existing != null) {
                requireManagedIdentity(existing, namespace, COMMENT, comment.seedKey());
                commentId = existing.entityId();
            } else {
                Long legacyId = comment.legacyOrdinal() == null
                        ? null
                        : mapper.findLegacyCommentId(postId, authorId, comment.legacyOrdinal());
                commentId = legacyId == null ? idGenerator.nextId() : legacyId;
                mapper.upsertIdentity(new SeedContentIdentity(
                        namespace, COMMENT, comment.seedKey(), commentId, version, null, null));
            }
            commentIds.put(comment.seedKey(), commentId);
        }

        Set<String> declaredKeys = new LinkedHashSet<>();
        for (SeedCommentDefinition comment : pack.comments()) {
            long id = requireIdentity(commentIds, comment.seedKey(), "comment");
            Long parentId = comment.parentSeedKey() == null
                    ? null
                    : requireIdentity(commentIds, comment.parentSeedKey(), "comment parent");
            long postId = resolveInteractionPostId(
                    comment.postSeedKey(), comment.postSlug(), postIds, externalPostIdsBySlug, "comment post");
            long authorId = requireIdentity(accountIds, comment.authorSeedKey(), "comment author");
            mapper.upsertSeedComment(new SeedCommentRow(
                    id, postId, parentId, authorId, comment.content(), comment.createdAt()));
            updateIdentityHash(namespace, COMMENT, comment.seedKey(), version,
                    sha256(json(List.of(postId, authorId, parentId == null ? 0L : parentId,
                            comment.content(), comment.createdAt().toString()))), "{}");
            declaredKeys.add(comment.seedKey());
        }
        // 只通过稳定身份表定位旧评论，避免按 Seed 邮箱后缀误伤普通用户留下的内容。
        mapper.deactivateSeedCommentsExcept(namespace, Set.copyOf(declaredKeys));
    }

    private void writeFollows(
            ContentPack pack, Map<String, Long> accountIds, String namespace, String version) {
        List<FollowPair> declaredPairs = new ArrayList<>();
        for (SeedFollowDefinition follow : pack.follows()) {
            long fromId = requireIdentity(accountIds, follow.fromAccountSeedKey(), "follow source");
            long toId = requireIdentity(accountIds, follow.toAccountSeedKey(), "follow target");
            SeedContentIdentity identity = mapper.findIdentity(namespace, FOLLOW, follow.seedKey());
            if (identity != null) {
                requireManagedIdentity(identity, namespace, FOLLOW, follow.seedKey());
                FollowingState identityRow = mapper.findFollowingById(identity.entityId());
                requireFollowingPair(identityRow, fromId, toId, "follow identity " + follow.seedKey());
            }
            FollowState existing = mapper.findFollowState(fromId, toId);
            if (identity != null && existing != null && existing.followingId() != null
                    && identity.entityId() != existing.followingId()) {
                throw new IllegalStateException("follow identity conflicts with existing account pair: "
                        + follow.seedKey());
            }
            long followingId = existing != null && existing.followingId() != null
                    ? existing.followingId()
                    : identity != null ? identity.entityId() : idGenerator.nextId();
            long followerId = existing != null && existing.followerId() != null
                    ? existing.followerId()
                    : idGenerator.nextId();
            if (existing != null && existing.followingId() != null) {
                requireFollowingPair(
                        mapper.findFollowingById(existing.followingId()), fromId, toId,
                        "following row " + existing.followingId());
            }
            if (existing != null && existing.followerId() != null) {
                requireFollowerPair(
                        mapper.findFollowerById(existing.followerId()), fromId, toId,
                        "follower row " + existing.followerId());
            }
            if (identity == null) {
                mapper.upsertIdentity(new SeedContentIdentity(
                        namespace, FOLLOW, follow.seedKey(), followingId, version, null, null));
            }
            mapper.upsertFollowing(new SeedFollowRow(followingId, fromId, toId, follow.createdAt()));
            mapper.upsertFollower(new SeedFollowRow(followerId, fromId, toId, follow.createdAt()));
            updateIdentityHash(namespace, FOLLOW, follow.seedKey(), version,
                    sha256(json(List.of(fromId, toId, follow.createdAt().toString()))),
                    json(Map.of("followerId", followerId)));
            declaredPairs.add(new FollowPair(fromId, toId));
        }
        Set<Long> seedAccountIds = Set.copyOf(accountIds.values());
        mapper.deactivateSeedFollowingExcept(seedAccountIds, List.copyOf(declaredPairs));
        mapper.deactivateSeedFollowerExcept(seedAccountIds, List.copyOf(declaredPairs));
    }

    static String contentHash(SeedPostDefinition post, long creatorId, PublishedContent published) {
        PublishedAsset markdown = requireMarkdown(published, post.seedKey());
        List<String> mediaHashes = new ArrayList<>();
        if (post.coverAsset() != null && !post.coverAsset().isBlank()) {
            mediaHashes.add(requireAsset(published, post.coverAsset(), "cover for " + post.seedKey()).sha256());
        }
        for (String inline : post.inlineAssets()) {
            mediaHashes.add(requireAsset(published, inline, "inline asset for " + post.seedKey()).sha256());
        }
        String canonical = String.join("\u0000",
                String.valueOf(creatorId), nullToEmpty(post.title()), nullToEmpty(post.slug()),
                nullToEmpty(post.description()), String.join("\u0001", post.tags()),
                post.publishTime().toString(), markdown.sha256(), String.join("\u0001", mediaHashes));
        return sha256(canonical);
    }

    private List<String> imageUrls(SeedPostDefinition post, PublishedContent published) {
        List<String> urls = new ArrayList<>();
        if (post.coverAsset() != null && !post.coverAsset().isBlank()) {
            urls.add(requireAsset(published, post.coverAsset(), "cover for " + post.seedKey()).publicUrl());
        }
        for (String inline : post.inlineAssets()) {
            urls.add(requireAsset(published, inline, "inline asset for " + post.seedKey()).publicUrl());
        }
        return urls;
    }

    private List<String> parseTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return List.of();
        }
        try {
            return List.copyOf(objectMapper.readValue(tagsJson, new TypeReference<List<String>>() { }));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid existing post tags JSON", exception);
        }
    }

    private void updateIdentityHash(
            String namespace, String type, String seedKey, String version, String hash, String metadata) {
        if (mapper.updateIdentityHash(namespace, type, seedKey, version, hash, metadata) == 0) {
            SeedContentIdentity identity = mapper.findIdentity(namespace, type, seedKey);
            if (identity == null) {
                throw new IllegalStateException("missing Seed identity while updating hash: " + type + "/" + seedKey);
            }
            requireManagedIdentity(identity, namespace, type, seedKey);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize content-pack database value", exception);
        }
    }

    private String seedEmail(SeedAccountDefinition account) {
        String localPart = account.legacyHandle() == null || account.legacyHandle().isBlank()
                ? account.seedKey()
                : account.legacyHandle();
        return localPart + SEED_EMAIL_SUFFIX;
    }

    private static PublishedAsset requireAsset(PublishedContent content, String key, String field) {
        PublishedAsset asset = content.assets().get(key);
        if (asset == null) {
            throw new IllegalArgumentException("missing published " + field + ": " + key);
        }
        return asset;
    }

    private static PublishedAsset requireMarkdown(PublishedContent content, String postSeedKey) {
        PublishedAsset markdown = content.markdownByPost().get(postSeedKey);
        if (markdown == null) {
            throw new IllegalArgumentException("missing published Markdown for " + postSeedKey);
        }
        return markdown;
    }

    private static long requireIdentity(Map<String, Long> identities, String key, String field) {
        Long value = identities.get(key);
        if (value == null || value <= 0) {
            throw new IllegalArgumentException("missing resolved " + field + ": " + key);
        }
        return value;
    }

    private Map<String, Long> resolveExternalPostIds(ContentPack pack) {
        Set<String> slugs = new LinkedHashSet<>();
        pack.comments().forEach(comment -> addExternalSlug(slugs, comment.postSlug()));
        pack.reactions().forEach(reaction -> addExternalSlug(slugs, reaction.postSlug()));
        pack.views().forEach(view -> addExternalSlug(slugs, view.postSlug()));
        if (slugs.isEmpty()) {
            return Map.of();
        }
        long ownerUserId = requireSiteOwnerUserId();
        Map<String, Long> resolved = new LinkedHashMap<>();
        for (String slug : slugs) {
            Long postId = mapper.findSiteOwnerPublicPostIdBySlug(slug, ownerUserId);
            if (postId == null || postId <= 0) {
                throw new IllegalArgumentException("missing site-owner public post: " + slug);
            }
            resolved.put(slug, postId);
        }
        return Collections.unmodifiableMap(resolved);
    }

    private static void addExternalSlug(Set<String> slugs, String slug) {
        if (slug != null && !slug.isBlank()) {
            slugs.add(slug);
        }
    }

    private static long resolveInteractionPostId(
            String postSeedKey,
            String postSlug,
            Map<String, Long> postIds,
            Map<String, Long> externalPostIdsBySlug,
            String field) {
        if (postSeedKey != null && !postSeedKey.isBlank()) {
            return requireIdentity(postIds, postSeedKey, field);
        }
        return requireIdentity(externalPostIdsBySlug, postSlug, field);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static void requireManagedIdentity(
            SeedContentIdentity identity, String namespace, String type, String seedKey) {
        if (!namespace.equals(identity.namespace())
                || !type.equals(identity.entityType())
                || !seedKey.equals(identity.seedKey())
                || identity.entityId() <= 0) {
            throw new IllegalStateException("invalid Seed identity for " + type + "/" + seedKey);
        }
    }

    private static void requireFollowingPair(
            FollowingState row, long fromId, long toId, String label) {
        if (row != null && (row.fromUserId() != fromId || row.toUserId() != toId)) {
            throw new IllegalStateException(label + " points to a different account pair");
        }
    }

    private static void requireFollowerPair(
            FollowerState row, long fromId, long toId, String label) {
        if (row != null && (row.fromUserId() != fromId || row.toUserId() != toId)) {
            throw new IllegalStateException(label + " points to a different account pair");
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /** Immutable account mutation row. */
    public record SeedUserRow(
            long id, String email, String nickname, String avatar, String bio, String handle,
            String gender, java.time.LocalDate birthday, String school, String tagsJson,
            Instant createdAt, Instant updatedAt) {
    }

    /** Immutable published post mutation row. */
    public record SeedPostRow(
            long id, long creatorId, String title, String slug, String description,
            String contentUrl, String contentObjectKey, String contentSha256, long contentSize,
            String tagsJson, String imgUrlsJson, Instant createTime, Instant publishTime) {
    }

    /** Immutable comment mutation row; every instance is manifest-owned. */
    public record SeedCommentRow(
            long id, long postId, Long parentId, long userId, String content, Instant createdAt) {
    }

    /** Immutable denormalized relation mutation row. */
    public record SeedFollowRow(long id, long fromUserId, long toUserId, Instant createdAt) {
    }

    /** Directed pair used to preserve declared relations during scoped deactivation. */
    public record FollowPair(long fromUserId, long toUserId) {
    }

    /** Stable account and post IDs consumed by post-commit runtime-state reconciliation. */
    public record ResolvedIdentities(
            String namespace,
            Map<String, Long> accountIds,
            Map<String, Long> postIds,
            Map<String, Long> externalPostIdsBySlug) {
        public ResolvedIdentities {
            namespace = requireText(namespace, "identity namespace");
            accountIds = Collections.unmodifiableMap(new LinkedHashMap<>(accountIds));
            postIds = Collections.unmodifiableMap(new LinkedHashMap<>(postIds));
            externalPostIdsBySlug = Collections.unmodifiableMap(new LinkedHashMap<>(externalPostIdsBySlug));
        }

        /** Creates legacy identities that do not include existing site-owner posts. */
        public ResolvedIdentities(String namespace, Map<String, Long> accountIds, Map<String, Long> postIds) {
            this(namespace, accountIds, postIds, Map.of());
        }
    }

    /** Exact relational outcome consumed by the Task 7 import orchestrator. */
    public record WriteResult(
            ResolvedIdentities identities,
            List<Long> changedPostIds,
            Map<Long, Integer> createdPostCountsByAuthor,
            List<Long> retiredPostIds,
            List<Long> retiredAuthorIds,
            List<String> unmatchedRetirementSlugs) {
        public WriteResult {
            changedPostIds = List.copyOf(changedPostIds);
            createdPostCountsByAuthor = Collections.unmodifiableMap(
                    new LinkedHashMap<>(createdPostCountsByAuthor));
            retiredPostIds = List.copyOf(retiredPostIds);
            retiredAuthorIds = List.copyOf(retiredAuthorIds);
            unmatchedRetirementSlugs = List.copyOf(unmatchedRetirementSlugs);
        }

        /** Creates a legacy write result without retirement outcomes. */
        public WriteResult(
                ResolvedIdentities identities,
                List<Long> changedPostIds,
                Map<Long, Integer> createdPostCountsByAuthor) {
            this(identities, changedPostIds, createdPostCountsByAuthor, List.of(), List.of(), List.of());
        }

        /**
         * Returns all resolved post IDs in pack order.
         *
         * @return all resolved post IDs in pack order
         */
        public List<Long> postIds() {
            return List.copyOf(identities.postIds().values());
        }
    }

    private record PostWriteState(
            Map<String, Long> postIds,
            List<Long> changedPostIds,
            Map<Long, Integer> createdPostCountsByAuthor) {
    }

    private record RetirementWriteState(
            List<Long> retiredPostIds,
            List<Long> retiredAuthorIds,
            List<String> unmatchedSlugs) {
    }
}
