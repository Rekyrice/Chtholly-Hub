package com.chtholly.seed.contentpack;

import com.chtholly.seed.contentpack.ContentPackSnapshotWriter.SnapshotRef;
import com.chtholly.seed.contentpack.model.ContentPack;
import com.chtholly.seed.contentpack.model.ContentPackManifest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;

class ContentPackSnapshotWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void given_focusedPublicRows_when_snapshot_then_writesRedactedJsonUnderProjectTempDirectory() throws Exception {
        ContentPackMapper mapper = mock(ContentPackMapper.class);
        when(mapper.snapshotSeedUsers("seed-content-v2")).thenReturn(List.of(new java.util.LinkedHashMap<>(Map.of(
                "seedKey", "shirokuma", "id", 101L, "handle", "shirokuma_on",
                "nickname", "白熊没关机", "avatar", "/uploads/avatar.webp",
                "passwordHash", "never-write-me", "phone", "13800000000", "accessToken", "secret-token"))));
        when(mapper.snapshotSeedPosts("seed-content-v2")).thenReturn(List.of(Map.of(
                "seedKey", "java-lock", "id", 201L, "creatorId", 101L,
                "contentUrl", "/uploads/post.md", "title", "锁住的不是代码")));
        when(mapper.snapshotSeedInteractions("seed-content-v2")).thenReturn(List.of(Map.of(
                "entityType", "COMMENT", "seedKey", "comment-1", "entityId", 301L,
                "postId", 201L, "userId", 101L, "content", "确实踩过")));
        ContentPack pack = pack(tempDir.resolve("content/seed/content-v2"), "complete");

        SnapshotRef ref = new ContentPackSnapshotWriter(mapper, new ObjectMapper(), tempDir)
                .write(pack, "run-safe-1");

        assertThat(ref.directory()).isEqualTo(tempDir.resolve(".codex-tmp/seed-content-v2/run-safe-1"));
        assertThat(ref.files()).containsExactly("accounts.json", "posts.json", "interactions.json");
        String allJson = Files.readString(ref.directory().resolve("accounts.json"))
                + Files.readString(ref.directory().resolve("posts.json"))
                + Files.readString(ref.directory().resolve("interactions.json"));
        assertThat(allJson).contains("shirokuma_on", "101", "201", "/uploads/post.md", "301");
        assertThat(allJson.toLowerCase()).doesNotContain("password", "phone", "token", "credential");
    }

    @Test
    void givenContentV3Pack_whenSnapshot_thenWritesUnderV3ProjectTempDirectory() {
        ContentPackMapper mapper = mock(ContentPackMapper.class);
        when(mapper.snapshotSeedUsers("launch-community")).thenReturn(List.of());
        when(mapper.snapshotSeedPosts("launch-community")).thenReturn(List.of());
        when(mapper.snapshotSeedInteractions("launch-community")).thenReturn(List.of());
        ContentPack pack = pack(
                tempDir.resolve("content/seed/content-v3"), "review", "content-v3", "launch-community");

        SnapshotRef ref = new ContentPackSnapshotWriter(mapper, new ObjectMapper(), tempDir)
                .write(pack, "run-v3");

        assertThat(ref.directory()).isEqualTo(tempDir.resolve(".codex-tmp/seed-content-v3/run-v3"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"content-beta", "content-v0", "../outside"})
    void givenUnsupportedManifestVersion_whenSnapshot_thenRejectsBeforeCreatingDirectory(String version) {
        ContentPack pack = pack(tempDir, "review", version, "launch-community");
        ContentPackSnapshotWriter writer = new ContentPackSnapshotWriter(
                mock(ContentPackMapper.class), new ObjectMapper(), tempDir);

        assertThatThrownBy(() -> writer.write(pack, "run-safe"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");

        assertThat(tempDir.resolve("outside")).doesNotExist();
    }

    @Test
    void given_unsafeRunId_when_snapshot_then_rejectsPathEscape() {
        ContentPackSnapshotWriter writer = new ContentPackSnapshotWriter(
                mock(ContentPackMapper.class), new ObjectMapper(), tempDir);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> writer.write(pack(tempDir, "complete"), "../outside"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runId");
    }

    @Test
    void given_secondJsonFails_when_snapshot_then_leavesNoFinalOrStagingDirectory() throws Exception {
        ContentPackMapper mapper = mock(ContentPackMapper.class);
        when(mapper.snapshotSeedUsers("seed-content-v2")).thenReturn(List.of(Map.of("id", 101L)));
        when(mapper.snapshotSeedPosts("seed-content-v2")).thenReturn(List.of(Map.of("id", 201L)));
        ObjectMapper failingMapper = spy(new ObjectMapper());
        AtomicInteger writes = new AtomicInteger();
        doAnswer(invocation -> {
            if (writes.incrementAndGet() == 2) {
                throw new IOException("second JSON failed");
            }
            return invocation.callRealMethod();
        }).when(failingMapper).writeValue(any(java.io.File.class), any());
        ContentPackSnapshotWriter writer = new ContentPackSnapshotWriter(mapper, failingMapper, tempDir);

        assertThatThrownBy(() -> writer.write(pack(tempDir, "complete"), "run-atomic-failure"))
                .isInstanceOf(RuntimeException.class);

        Path base = tempDir.resolve(".codex-tmp/seed-content-v2");
        assertThat(base.resolve("run-atomic-failure")).doesNotExist();
        try (var children = Files.list(base)) {
            assertThat(children.map(path -> path.getFileName().toString()).toList())
                    .noneMatch(name -> name.startsWith(".staging-run-atomic-failure-"));
        }
    }

    private ContentPack pack(Path root, String stage) {
        return pack(root, stage, "content-v2", "seed-content-v2");
    }

    private ContentPack pack(Path root, String stage, String version, String namespace) {
        return new ContentPack(root, new ContentPackManifest(
                version, namespace, stage, 0, 0, Map.of()),
                List.of(), Map.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
