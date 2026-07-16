package com.chtholly.seed.contentpack;

import com.chtholly.seed.contentpack.model.SeedContentIdentity;
import com.chtholly.seed.contentpack.ContentPackDatabaseWriter.FollowPair;
import com.chtholly.seed.contentpack.ContentPackDatabaseWriter.SeedCommentRow;
import com.chtholly.seed.contentpack.ContentPackDatabaseWriter.SeedFollowRow;
import com.chtholly.seed.contentpack.ContentPackDatabaseWriter.SeedPostRow;
import com.chtholly.seed.contentpack.ContentPackDatabaseWriter.SeedUserRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Persists immutable seed identities and locates entities created by the legacy seed system.
 */
@Mapper
public interface ContentPackMapper {

    /**
     * Finds the stable identity assigned to one seed definition.
     *
     * @param namespace stable content family namespace
     * @param entityType entity discriminator such as ACCOUNT or POST
     * @param seedKey immutable key within the namespace
     * @return stored identity, or {@code null} when it has not been assigned
     */
    SeedContentIdentity findIdentity(
            @Param("namespace") String namespace,
            @Param("entityType") String entityType,
            @Param("seedKey") String seedKey);

    /**
     * Finds a legacy seed user by its synthetic email address.
     *
     * @param email legacy synthetic email
     * @return user ID, or {@code null} when absent
     */
    Long findLegacyUserId(@Param("email") String email);

    /**
     * Finds a legacy seed post by its original public slug.
     *
     * @param slug original public slug
     * @return post ID, or {@code null} when absent
     */
    Long findLegacyPostId(@Param("slug") String slug);

    /**
     * Resolves an existing public post owned by the configured site owner.
     *
     * @param slug exact public slug
     * @param ownerUserId configured site-owner user ID
     * @return post ID, or {@code null} when the slug is absent or outside the allowed owner/public boundary
     */
    Long findSiteOwnerPublicPostIdBySlug(
            @Param("slug") String slug,
            @Param("ownerUserId") long ownerUserId);

    /**
     * Inserts an identity or refreshes metadata only when the immutable mapping agrees.
     *
     * @param row resolved stable identity
     */
    void upsertIdentity(SeedContentIdentity row);

    /**
     * Updates import state after an entity has been written successfully.
     *
     * @param namespace stable content family namespace
     * @param entityType entity discriminator
     * @param seedKey immutable definition key
     * @param packVersion imported pack version
     * @param contentHash imported content hash
     * @param metadataJson serialized import metadata
     * @return number of updated identity rows
     */
    int updateIdentityHash(
            @Param("namespace") String namespace,
            @Param("entityType") String entityType,
            @Param("seedKey") String seedKey,
            @Param("packVersion") String packVersion,
            @Param("contentHash") String contentHash,
            @Param("metadataJson") String metadataJson);

    /** @param row identity-resolved user mutation @return affected rows */
    int updateSeedUserById(SeedUserRow row);

    /** @param id resolved user ID @return whether the row exists */
    boolean seedUserExistsById(@Param("id") long id);

    /** @param row new Seed user @return affected rows */
    int insertSeedUser(SeedUserRow row);

    /** @param id resolved post ID @return current post state or {@code null} */
    PostState findPostStateById(@Param("id") long id);

    /** @param row published post mutation @return affected rows */
    int updateSeedPostById(SeedPostRow row);

    /** @param row new published post @return affected rows */
    int insertSeedPost(SeedPostRow row);

    /**
     * Locks posts whose complete public slugs appear in the approved retirement allowlist.
     *
     * @param slugs exact declared slugs
     * @return matching rows in database order
     */
    List<RetirementCandidate> findRetirementCandidates(@Param("slugs") List<String> slugs);

    /**
     * Soft-deletes one currently published retirement candidate.
     *
     * @param id candidate post ID
     * @return affected row count
     */
    int softDeleteRetirementPost(@Param("id") long id);

    /**
     * @param postId resolved post ID
     * @param authorId resolved Seed author ID
     * @param ordinal zero-based legacy order
     * @return legacy comment ID or {@code null}
     */
    Long findLegacyCommentId(
            @Param("postId") long postId,
            @Param("authorId") long authorId,
            @Param("ordinal") int ordinal);

    /** @param row manifest-owned comment @return affected rows */
    int upsertSeedComment(SeedCommentRow row);

    /**
     * @param namespace stable content namespace
     * @param seedKeys currently declared comment keys
     * @return affected rows
     */
    int deactivateSeedCommentsExcept(
            @Param("namespace") String namespace,
            @Param("seedKeys") Set<String> seedKeys);

    /**
     * @param fromUserId follower account ID
     * @param toUserId followed account ID
     * @return both relation-row IDs, possibly absent
     */
    FollowState findFollowState(
            @Param("fromUserId") long fromUserId,
            @Param("toUserId") long toUserId);

    /** @param id following row ID @return row state or {@code null} */
    FollowingState findFollowingById(@Param("id") long id);

    /** @param id follower row ID @return row state or {@code null} */
    FollowerState findFollowerById(@Param("id") long id);

    /** @param row following-side mutation @return affected rows */
    int upsertFollowing(SeedFollowRow row);

    /** @param row follower-side mutation @return affected rows */
    int upsertFollower(SeedFollowRow row);

    /**
     * @param namespace stable content-pack namespace
     * @param declaredPairs currently declared directed pairs
     * @return affected rows
     */
    int deactivateSeedFollowingExcept(
            @Param("namespace") String namespace,
            @Param("declaredPairs") List<FollowPair> declaredPairs);

    /**
     * @param namespace stable content-pack namespace
     * @param declaredPairs currently declared directed pairs
     * @return affected rows
     */
    int deactivateSeedFollowerExcept(
            @Param("namespace") String namespace,
            @Param("declaredPairs") List<FollowPair> declaredPairs);

    /** @param namespace stable content namespace @return redacted public Seed user rows */
    List<Map<String, Object>> snapshotSeedUsers(@Param("namespace") String namespace);

    /** @param namespace stable content namespace @return redacted public Seed post rows */
    List<Map<String, Object>> snapshotSeedPosts(@Param("namespace") String namespace);

    /** @param namespace stable content namespace @return Seed-managed interaction rows */
    List<Map<String, Object>> snapshotSeedInteractions(@Param("namespace") String namespace);

    /** Minimal current post state used for idempotency and tag reconciliation. */
    record PostState(long id, long creatorId, String tagsJson, String contentObjectKey) {
    }

    /** Locked post state used by strict allowlist retirement. */
    record RetirementCandidate(long id, long creatorId, String slug, String status, String tagsJson) {
    }

    /** Existing IDs for both copies of a directed follow relation. */
    record FollowState(Long followingId, Long followerId) {
    }

    /** Stored following-side directed pair. */
    record FollowingState(long id, long fromUserId, long toUserId) {
    }

    /** Stored follower-side directed pair normalized to from/to semantics. */
    record FollowerState(long id, long fromUserId, long toUserId) {
    }
}
