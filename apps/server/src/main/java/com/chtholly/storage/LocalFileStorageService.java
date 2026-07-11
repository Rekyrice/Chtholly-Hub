package com.chtholly.storage;

import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.storage.config.StorageProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.DigestInputStream;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * Local filesystem storage used when OSS is disabled.
 *
 * <p>Content-addressed seed objects are immutable. They are staged beside the final path and
 * atomically linked into place, so an interrupted upload cannot expose partial bytes.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "storage.type", havingValue = "local", matchIfMissing = true)
public class LocalFileStorageService implements StorageService {

    private static final int PRESIGN_EXPIRES_SECONDS = 600;
    private static final String UPLOAD_ENDPOINT = "/api/v1/storage/upload";
    private static final String IMMUTABLE_SEED_PREFIX = "seed/content-v2/";

    private final StorageProperties props;
    private Path basePath;

    @PostConstruct
    void init() {
        Path configuredPath = Paths.get(props.getLocal().getBasePath()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(configuredPath);
            basePath = configuredPath.toRealPath();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to initialize local storage directory: " + configuredPath, exception);
        }
    }

    @Override
    public String uploadAvatar(long userId, InputStream inputStream, String contentType) throws IOException {
        String normalizedType = normalizeContentType(contentType);
        String ext = ImageUploadValidator.extensionForContentType(normalizedType);
        String date = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE);
        String objectKey = "avatars/" + userId + "/" + date + "/" + UUID.randomUUID() + ext;
        writeObject(objectKey, inputStream, -1, null);
        return publicUrl(objectKey);
    }

    @Override
    public PresignedUrl generatePresignedPutUrl(String objectKey, String contentType) {
        StorageObjectKeyValidator.assertSafeObjectKey(objectKey);
        Map<String, String> headers = contentType != null && !contentType.isBlank()
                ? Map.of("Content-Type", contentType.trim().toLowerCase())
                : Map.of();
        return new PresignedUrl(UPLOAD_ENDPOINT, headers, PRESIGN_EXPIRES_SECONDS, "POST");
    }

    @Override
    public void uploadObject(String objectKey, InputStream inputStream, String contentType, long size)
            throws IOException {
        StorageObjectKeyValidator.assertSafeObjectKey(objectKey);
        writeObject(objectKey, inputStream, size, null);
    }

    @Override
    public void uploadVerifiedObject(
            String objectKey,
            InputStream inputStream,
            String contentType,
            long size,
            String sha256) throws IOException {
        StorageObjectKeyValidator.assertSafeObjectKey(objectKey);
        requireSha256(sha256);
        writeObject(objectKey, inputStream, size, sha256.toLowerCase());
    }

    @Override
    public boolean objectExists(String objectKey) {
        StorageObjectKeyValidator.assertSafeObjectKey(objectKey);
        return resolveExistingObjectPath(objectKey) != null;
    }

    @Override
    public boolean objectMatches(String objectKey, String sha256, long size) throws IOException {
        StorageObjectKeyValidator.assertSafeObjectKey(objectKey);
        requireSha256(sha256);
        Path target = resolveExistingObjectPath(objectKey);
        if (target == null || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        FileIdentity identity = digest(target);
        return identity.size() == size && identity.sha256().equalsIgnoreCase(sha256);
    }

    @Override
    public void deleteObject(String objectKey) {
        StorageObjectKeyValidator.assertSafeObjectKey(objectKey);
        Path target = resolveExistingObjectPath(objectKey);
        if (target == null) {
            return;
        }
        try {
            Files.deleteIfExists(target);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "删除文件失败");
        }
    }

    Path resolveObjectPath(String objectKey) {
        Path target = basePath.resolve(objectKey).normalize();
        if (!target.startsWith(basePath)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "objectKey 非法");
        }
        return target;
    }

    @Override
    public String resolvePublicUrl(String objectKey) {
        StorageObjectKeyValidator.assertSafeObjectKey(objectKey);
        return publicUrl(objectKey);
    }

    private void writeObject(
            String objectKey,
            InputStream inputStream,
            long expectedSize,
            String expectedSha256) throws IOException {
        Path target = prepareWriteTarget(objectKey);
        Path temporary = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
        try {
            FileIdentity staged = copyAndDigest(inputStream, temporary);
            if (expectedSize >= 0 && staged.size() != expectedSize) {
                throw new IOException(
                        "upload size mismatch for " + objectKey + ": expected " + expectedSize + ", actual " + staged.size());
            }
            if (expectedSha256 != null && !staged.sha256().equalsIgnoreCase(expectedSha256)) {
                throw new IOException("upload sha256 mismatch for " + objectKey);
            }
            if (objectKey.startsWith(IMMUTABLE_SEED_PREFIX)) {
                installImmutableObject(target, temporary, staged);
            } else {
                moveReplacing(target, temporary);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path prepareWriteTarget(String objectKey) throws IOException {
        Path target = resolveObjectPath(objectKey);
        Path relativeParent = basePath.relativize(target.getParent());
        Path current = basePath;
        for (Path segment : relativeParent) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current)) {
                    throw new IllegalArgumentException("storage path contains symbolic link: " + objectKey);
                }
                if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("storage path component is not a directory: " + current);
                }
            } else {
                Files.createDirectory(current);
            }
            Path realCurrent = current.toRealPath();
            if (!realCurrent.startsWith(basePath)) {
                throw new IllegalArgumentException("storage path escapes root: " + objectKey);
            }
        }
        if (Files.isSymbolicLink(target)) {
            throw new IllegalArgumentException("storage target is a symbolic link: " + objectKey);
        }
        return target;
    }

    private Path resolveExistingObjectPath(String objectKey) {
        Path target = resolveObjectPath(objectKey);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (Files.isSymbolicLink(target)) {
            throw new IllegalArgumentException("storage target is a symbolic link: " + objectKey);
        }
        try {
            Path realTarget = target.toRealPath();
            if (!realTarget.startsWith(basePath)) {
                throw new IllegalArgumentException("storage path escapes root: " + objectKey);
            }
            return realTarget;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to resolve stored object: " + objectKey, exception);
        }
    }

    private void installImmutableObject(Path target, Path temporary, FileIdentity staged) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            assertExistingMatches(target, staged);
            return;
        }
        try {
            // 同目录硬链接原子创建最终名称，并且在目标已存在时不会覆盖不可变对象。
            Files.createLink(target, temporary);
        } catch (FileAlreadyExistsException exception) {
            assertExistingMatches(target, staged);
        } catch (UnsupportedOperationException exception) {
            throw new IOException("atomic immutable install is unsupported for " + target, exception);
        }
    }

    private void assertExistingMatches(Path target, FileIdentity staged) throws IOException {
        if (Files.isSymbolicLink(target)) {
            throw new IllegalArgumentException("immutable storage target is a symbolic link: " + target);
        }
        FileIdentity existing = digest(target);
        if (!existing.equals(staged)) {
            throw new IOException("immutable object already exists with different content: " + target);
        }
    }

    private void moveReplacing(Path target, Path temporary) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private FileIdentity copyAndDigest(InputStream inputStream, Path target) throws IOException {
        MessageDigest digest = newDigest();
        long size;
        DigestInputStream source = new DigestInputStream(inputStream, digest);
        try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.TRUNCATE_EXISTING)) {
            size = source.transferTo(output);
        }
        return new FileIdentity(size, HexFormat.of().formatHex(digest.digest()));
    }

    private FileIdentity digest(Path path) throws IOException {
        MessageDigest digest = newDigest();
        long size;
        try (InputStream input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             DigestInputStream source = new DigestInputStream(input, digest)) {
            size = source.transferTo(OutputStream.nullOutputStream());
        }
        return new FileIdentity(size, HexFormat.of().formatHex(digest.digest()));
    }

    private MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void requireSha256(String sha256) {
        if (sha256 == null || !sha256.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("sha256 must contain 64 hexadecimal characters");
        }
    }

    private String publicUrl(String objectKey) {
        String prefix = props.getLocal().getPublicUrlPrefix().replaceAll("/$", "");
        return prefix + "/" + objectKey;
    }

    private static String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Content-Type 不能为空");
        }
        return contentType.trim().toLowerCase();
    }

    private record FileIdentity(long size, String sha256) {
    }
}
