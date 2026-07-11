package com.chtholly.seed.contentpack;

import com.chtholly.seed.contentpack.model.SeedContentIdentity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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
}
