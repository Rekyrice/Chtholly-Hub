package com.chtholly.seed.contentpack;

import com.chtholly.post.id.SnowflakeIdGenerator;
import com.chtholly.seed.contentpack.model.SeedAccountDefinition;
import com.chtholly.seed.contentpack.model.SeedContentIdentity;
import com.chtholly.seed.contentpack.model.SeedPostDefinition;

import java.util.Objects;

/**
 * Resolves immutable content-pack keys to persistent account and post IDs.
 *
 * <p>The resolver performs a one-time legacy lookup before generating a Snowflake ID. Once an
 * identity exists, public handles and slugs can change without changing the underlying entity.
 */
public final class ContentPackIdentityResolver {

    private static final String ACCOUNT = "ACCOUNT";
    private static final String POST = "POST";
    private static final String LEGACY_EMAIL_SUFFIX = "@seed.chtholly.invalid";

    private final String namespace;
    private final ContentPackMapper mapper;
    private final SnowflakeIdGenerator idGenerator;

    /**
     * Creates a resolver for one stable content namespace.
     *
     * @param namespace stable namespace independent of the pack version
     * @param mapper identity persistence mapper
     * @param idGenerator generator for entities with no previous identity
     */
    public ContentPackIdentityResolver(
            String namespace,
            ContentPackMapper mapper,
            SnowflakeIdGenerator idGenerator) {
        this.namespace = requireText(namespace, "namespace");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    }

    /**
     * Resolves an account definition to its original or newly allocated user ID.
     *
     * @param definition account definition containing the immutable and legacy keys
     * @param packVersion current content-pack version
     * @return stable user ID
     */
    public long resolveAccountId(SeedAccountDefinition definition, String packVersion) {
        Objects.requireNonNull(definition, "definition");
        String seedKey = requireText(definition.seedKey(), "account seedKey");
        return resolve(
                ACCOUNT,
                seedKey,
                requireText(packVersion, "packVersion"),
                () -> findLegacyAccountId(definition.legacyHandle()));
    }

    /**
     * Resolves a post definition to its original or newly allocated post ID.
     *
     * @param definition post definition containing the immutable and legacy keys
     * @param packVersion current content-pack version
     * @return stable post ID
     */
    public long resolvePostId(SeedPostDefinition definition, String packVersion) {
        Objects.requireNonNull(definition, "definition");
        String seedKey = requireText(definition.seedKey(), "post seedKey");
        return resolve(
                POST,
                seedKey,
                requireText(packVersion, "packVersion"),
                () -> findLegacyPostId(definition.legacySlug()));
    }

    private long resolve(String entityType, String seedKey, String packVersion, LegacyLookup legacyLookup) {
        SeedContentIdentity existing = mapper.findIdentity(namespace, entityType, seedKey);
        SeedContentIdentity resolved;
        boolean generated = false;
        if (existing != null) {
            validateExisting(existing, entityType, seedKey);
            resolved = existing;
        } else {
            Long legacyId = legacyLookup.find();
            generated = legacyId == null;
            long entityId = generated ? idGenerator.nextId() : legacyId;
            requirePositiveId(entityId, entityType, seedKey);
            resolved = new SeedContentIdentity(
                    namespace, entityType, seedKey, entityId, packVersion, null, null);
        }

        // 每次解析都立即固化映射，避免后续公开 handle/slug 改名后失去旧记录的身份锚点。
        try {
            mapper.upsertIdentity(resolved);
            return resolved.entityId();
        } catch (RuntimeException exception) {
            if (!generated) {
                throw exception;
            }

            // 两个导入进程可能同时看不到新映射并各自生成 ID；只有确认同一个 key 已有赢家时才收敛。
            SeedContentIdentity winner = mapper.findIdentity(namespace, entityType, seedKey);
            if (winner == null) {
                throw exception;
            }
            validateExisting(winner, entityType, seedKey);
            return winner.entityId();
        }
    }

    private Long findLegacyAccountId(String legacyHandle) {
        if (legacyHandle == null || legacyHandle.isBlank()) {
            return null;
        }
        return mapper.findLegacyUserId(legacyHandle + LEGACY_EMAIL_SUFFIX);
    }

    private Long findLegacyPostId(String legacySlug) {
        if (legacySlug == null || legacySlug.isBlank()) {
            return null;
        }
        return mapper.findLegacyPostId(legacySlug);
    }

    private void validateExisting(SeedContentIdentity identity, String entityType, String seedKey) {
        if (!namespace.equals(identity.namespace())
                || !entityType.equals(identity.entityType())
                || !seedKey.equals(identity.seedKey())) {
            throw new IllegalStateException(
                    "Seed identity mismatch for " + namespace + "/" + entityType + "/" + seedKey);
        }
        requirePositiveId(identity.entityId(), entityType, seedKey);
    }

    private void requirePositiveId(long entityId, String entityType, String seedKey) {
        if (entityId <= 0) {
            throw new IllegalStateException(
                    "Invalid entity ID for " + namespace + "/" + entityType + "/" + seedKey + ": " + entityId);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    @FunctionalInterface
    private interface LegacyLookup {
        Long find();
    }
}
