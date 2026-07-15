package com.chtholly.storage;

import java.io.IOException;
import java.io.InputStream;

/**
 * Object storage boundary shared by local filesystem and OSS implementations.
 */
public interface StorageService {

    /**
     * Uploads a user avatar and returns its public URL.
     *
     * @param userId avatar owner
     * @param inputStream avatar bytes
     * @param contentType image content type
     * @return public URL
     * @throws IOException when upload fails
     */
    String uploadAvatar(long userId, InputStream inputStream, String contentType) throws IOException;

    /**
     * Generates a direct or local-service upload URL.
     *
     * @param objectKey storage object key
     * @param contentType expected content type
     * @return upload contract
     */
    PresignedUrl generatePresignedPutUrl(String objectKey, String contentType);

    /**
     * Writes ordinary application content to the supplied object key.
     *
     * @param objectKey storage object key
     * @param inputStream object bytes
     * @param contentType content type
     * @param size declared byte length
     * @throws IOException when upload fails
     */
    void uploadObject(String objectKey, InputStream inputStream, String contentType, long size) throws IOException;

    /**
     * Uploads an object together with its verified SHA-256 identity.
     *
     * @param objectKey storage object key
     * @param inputStream object bytes
     * @param contentType verified content type
     * @param size exact byte length
     * @param sha256 full lowercase SHA-256 digest
     * @throws IOException when the bytes cannot be persisted exactly
     */
    void uploadVerifiedObject(
            String objectKey,
            InputStream inputStream,
            String contentType,
            long size,
            String sha256) throws IOException;

    /**
     * Checks whether an object key is already present without mutating it.
     *
     * @param objectKey storage object key
     * @return {@code true} when the object already exists
     */
    boolean objectExists(String objectKey);

    /**
     * Verifies that an existing object has the exact expected identity.
     *
     * @param objectKey storage object key
     * @param sha256 full lowercase SHA-256 digest
     * @param size exact byte length
     * @return {@code true} only when both digest and length match
     * @throws IOException when stored bytes cannot be verified
     */
    boolean objectMatches(String objectKey, String sha256, long size) throws IOException;

    /**
     * Deletes one object when present.
     *
     * @param objectKey storage object key
     */
    void deleteObject(String objectKey);

    /**
     * Resolves a publicly accessible URL for an object key.
     *
     * @param objectKey storage object key
     * @return absolute or site-relative public URL
     */
    String resolvePublicUrl(String objectKey);
}
