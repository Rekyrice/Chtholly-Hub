package com.chtholly.seed.contentpack;

import com.chtholly.seed.contentpack.model.ContentPack;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Writes a focused, redacted pre-import MySQL snapshot beneath the project temp directory. */
@Component
public final class ContentPackSnapshotWriter {

    private static final Pattern SAFE_RUN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final List<String> FILES = List.of("accounts.json", "posts.json", "interactions.json");
    private static final List<String> SECRET_FRAGMENTS = List.of(
            "password", "passwd", "phone", "mobile", "token", "credential", "secret", "email");

    private final ContentPackMapper mapper;
    private final ObjectMapper objectMapper;
    private final Path projectRoot;

    /**
     * Creates the application snapshot writer.
     *
     * @param mapper focused Seed-owned snapshot queries
     * @param objectMapper JSON serializer
     * @param projectRoot project root containing the ignored {@code .codex-tmp} directory
     */
    @Autowired
    public ContentPackSnapshotWriter(
            ContentPackMapper mapper,
            ObjectMapper objectMapper,
            @Value("${seed.content-pack.project-root:../..}") String projectRoot) {
        this(mapper, objectMapper, Path.of(projectRoot));
    }

    /** Package-independent constructor used by focused tests and command wiring. */
    public ContentPackSnapshotWriter(ContentPackMapper mapper, ObjectMapper objectMapper, Path projectRoot) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
    }

    /**
     * Writes public Seed rows as three atomic UTF-8 JSON files.
     *
     * @param pack validated content pack
     * @param runId filesystem-safe unique run identifier
     * @return snapshot directory and filenames
     */
    public SnapshotRef write(ContentPack pack, String runId) {
        Objects.requireNonNull(pack, "pack");
        if (runId == null || !SAFE_RUN_ID.matcher(runId).matches()) {
            throw new IllegalArgumentException("unsafe snapshot runId: " + runId);
        }
        String namespace = Objects.requireNonNull(pack.manifest(), "manifest").namespace();
        Path base = projectRoot.resolve(".codex-tmp/seed-content-v2").normalize();
        Path directory = base.resolve(runId).normalize();
        if (!directory.startsWith(base)) {
            throw new IllegalArgumentException("snapshot runId escapes project temp directory: " + runId);
        }
        Path staging = base.resolve(".staging-" + runId + "-" + UUID.randomUUID()).normalize();
        try {
            ensureNoSymbolicLink(projectRoot, base);
            Files.createDirectories(base);
            ensureRealPathInsideProject(base);
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("snapshot runId already exists: " + runId);
            }
            Files.createDirectory(staging);
            ensureRealPathInsideProject(staging);
            writeJson(staging.resolve(FILES.get(0)), sanitize(mapper.snapshotSeedUsers(namespace)));
            writeJson(staging.resolve(FILES.get(1)), sanitize(mapper.snapshotSeedPosts(namespace)));
            writeJson(staging.resolve(FILES.get(2)), sanitize(mapper.snapshotSeedInteractions(namespace)));
            // 三个文件全部关闭并落盘后才公开目录，读者不会观察到半套快照。
            Files.move(staging, directory, StandardCopyOption.ATOMIC_MOVE);
            return new SnapshotRef(directory, FILES);
        } catch (IOException | RuntimeException exception) {
            try {
                deleteTree(staging);
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            if (exception instanceof IOException ioException) {
                throw new UncheckedIOException("Failed to write redacted content-pack snapshot", ioException);
            }
            throw (RuntimeException) exception;
        }
    }

    private List<Map<String, Object>> sanitize(List<Map<String, Object>> rows) {
        if (rows == null) {
            return List.of();
        }
        return rows.stream().map(row -> {
            Map<String, Object> safe = new LinkedHashMap<>();
            if (row != null) {
                row.forEach((key, value) -> {
                    String folded = key == null ? "" : key.toLowerCase(Locale.ROOT);
                    if (SECRET_FRAGMENTS.stream().noneMatch(folded::contains)) {
                        safe.put(key, value);
                    }
                });
            }
            return safe;
        }).toList();
    }

    private void writeJson(Path destination, Object value) throws IOException {
        objectMapper.writeValue(destination.toFile(), value);
        try (FileChannel channel = FileChannel.open(destination, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private void ensureRealPathInsideProject(Path path) throws IOException {
        Path realProject = projectRoot.toRealPath();
        Path realPath = path.toRealPath();
        if (!realPath.startsWith(realProject)) {
            throw new IllegalArgumentException("snapshot path escapes project root: " + path);
        }
    }

    private void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private void ensureNoSymbolicLink(Path trustedRoot, Path destination) throws IOException {
        Path current = trustedRoot;
        if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
            throw new IllegalArgumentException("project root must not be a symbolic link: " + trustedRoot);
        }
        Path relative = trustedRoot.relativize(destination);
        for (Path part : relative) {
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("snapshot path contains a symbolic link: " + current);
            }
        }
    }

    /**
     * Stable reference included in import reports.
     *
     * @param directory snapshot directory
     * @param files redacted JSON filenames
     */
    public record SnapshotRef(Path directory, List<String> files) {
        public SnapshotRef {
            directory = directory.toAbsolutePath().normalize();
            files = List.copyOf(files);
        }
    }
}
