package com.chtholly.seed.contentpack;

import com.chtholly.post.id.SnowflakeIdGenerator;
import com.chtholly.seed.contentpack.ContentPackDatabaseWriter.SeedCommentRow;
import com.chtholly.seed.contentpack.ContentPackDatabaseWriter.SeedFollowRow;
import com.chtholly.seed.contentpack.ContentPackDatabaseWriter.SeedPostRow;
import com.chtholly.seed.contentpack.ContentPackDatabaseWriter.SeedUserRow;
import com.chtholly.seed.contentpack.ContentPackDatabaseWriter.WriteResult;
import com.chtholly.seed.contentpack.ContentPackMapper.FollowState;
import com.chtholly.seed.contentpack.ContentPackMapper.PostState;
import com.chtholly.seed.contentpack.ContentPackMapper.FollowingState;
import com.chtholly.seed.contentpack.ContentPackMapper.FollowerState;
import com.chtholly.seed.contentpack.ContentPackMediaPublisher.PublishedAsset;
import com.chtholly.seed.contentpack.ContentPackMediaPublisher.PublishedContent;
import com.chtholly.seed.contentpack.model.ContentPack;
import com.chtholly.seed.contentpack.model.ContentPackManifest;
import com.chtholly.seed.contentpack.model.SeedAccountDefinition;
import com.chtholly.seed.contentpack.model.SeedCommentDefinition;
import com.chtholly.seed.contentpack.model.SeedContentIdentity;
import com.chtholly.seed.contentpack.model.SeedFollowDefinition;
import com.chtholly.seed.contentpack.model.SeedPostDefinition;
import com.chtholly.tag.service.TagService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentPackDatabaseWriterTest {

    private static final String NAMESPACE = "launch-community";
    private static final String VERSION = "content-v2";

    @Mock
    private ContentPackMapper mapper;
    @Mock
    private SnowflakeIdGenerator idGenerator;
    @Mock
    private TagService tagService;

    private ContentPackDatabaseWriter writer;

    @BeforeEach
    void setUp() {
        writer = new ContentPackDatabaseWriter(
                mapper, idGenerator, tagService, new ObjectMapper().findAndRegisterModules());
        lenient().when(mapper.updateIdentityHash(any(), any(), any(), any(), any(), any())).thenReturn(1);
    }

    @Test
    void givenExistingAccountAndPost_whenWrite_thenUpdatesSameIdsAndSyncsTags() {
        stubAccount("author", 42L);
        when(mapper.findIdentity(NAMESPACE, "POST", "post-one"))
                .thenReturn(identity("POST", "post-one", 99L, "old-hash"));
        when(mapper.findPostStateById(99L))
                .thenReturn(new PostState(99L, 42L, "[\"old\"]", "old.md"));

        WriteResult result = writer.write(pack(List.of(account("author")), List.of(post("post-one", "author")),
                List.of(), List.of()), published("post-one"));

        verify(mapper).updateSeedUserById(any(SeedUserRow.class));
        verify(mapper).updateSeedPostById(any(SeedPostRow.class));
        ArgumentCaptor<SeedUserRow> user = ArgumentCaptor.forClass(SeedUserRow.class);
        verify(mapper).updateSeedUserById(user.capture());
        assertThat(user.getValue().id()).isEqualTo(42L);
        assertThat(user.getValue().handle()).isEqualTo("shirokuma_on");
        ArgumentCaptor<SeedPostRow> post = ArgumentCaptor.forClass(SeedPostRow.class);
        verify(mapper).updateSeedPostById(post.capture());
        assertThat(post.getValue().id()).isEqualTo(99L);
        assertThat(post.getValue().creatorId()).isEqualTo(42L);
        assertThat(post.getValue().imgUrlsJson()).isEqualTo("[\"/media/cover.webp\",\"/media/inline.webp\"]");
        verify(tagService).syncPublishedPostTags(42L, List.of("old"), List.of("Java", "MySQL"));
        assertThat(result.postIds()).containsExactly(99L);
        assertThat(result.changedPostIds()).containsExactly(99L);
        assertThat(result.createdPostCountsByAuthor()).isEmpty();
    }

    @Test
    void givenUnchangedPostHash_whenWrite_thenSkipsPostMutationAndTagSync() {
        stubAccount("author", 42L);
        SeedPostDefinition definition = post("post-one", "author");
        PublishedContent content = published("post-one");
        String hash = ContentPackDatabaseWriter.contentHash(definition, 42L, content);
        when(mapper.findIdentity(NAMESPACE, "POST", "post-one"))
                .thenReturn(identity("POST", "post-one", 99L, hash));
        when(mapper.findPostStateById(99L))
                .thenReturn(new PostState(99L, 42L, "[\"Java\",\"MySQL\"]", "posts/post-one.md"));

        WriteResult result = writer.write(pack(List.of(account("author")), List.of(definition),
                List.of(), List.of()), content);

        verify(mapper, never()).updateSeedPostById(any());
        verify(mapper, never()).insertSeedPost(any());
        verifyNoMoreInteractions(tagService);
        assertThat(result.changedPostIds()).isEmpty();
    }

    @Test
    void givenNewPost_whenWrite_thenInsertsAndReportsCreatedCount() {
        stubAccount("author", 42L);
        when(mapper.findIdentity(NAMESPACE, "POST", "post-one")).thenReturn(null);
        when(mapper.findLegacyPostId("legacy-post-one")).thenReturn(null);
        when(idGenerator.nextId()).thenReturn(101L);
        when(mapper.findPostStateById(101L)).thenReturn(null);

        WriteResult result = writer.write(pack(List.of(account("author")), List.of(post("post-one", "author")),
                List.of(), List.of()), published("post-one"));

        verify(mapper).insertSeedPost(any(SeedPostRow.class));
        verify(tagService).syncPublishedPostTags(42L, List.of(), List.of("Java", "MySQL"));
        assertThat(result.changedPostIds()).containsExactly(101L);
        assertThat(result.createdPostCountsByAuthor()).containsEntry(42L, 1);
    }

    @Test
    void givenPostWithoutCoverOrInlineMedia_whenWrite_thenPersistsEmptyImageListAndStableHash() {
        stubAccount("author", 42L);
        when(mapper.findIdentity(NAMESPACE, "POST", "post-one")).thenReturn(null);
        when(mapper.findLegacyPostId("legacy-post-one")).thenReturn(null);
        when(idGenerator.nextId()).thenReturn(101L);
        when(mapper.findPostStateById(101L)).thenReturn(null);
        SeedPostDefinition base = post("post-one", "author");
        SeedPostDefinition noMedia = new SeedPostDefinition(
                base.seedKey(), base.legacySlug(), base.authorSeedKey(), base.title(), base.slug(), base.description(),
                base.category(), base.tags(), base.publishTime(), base.markdownFile(), null, List.of(), base.brief(),
                base.markdown());

        PublishedContent content = published("post-one");
        String firstHash = ContentPackDatabaseWriter.contentHash(noMedia, 42L, content);
        String secondHash = ContentPackDatabaseWriter.contentHash(noMedia, 42L, content);
        writer.write(pack(List.of(account("author")), List.of(noMedia), List.of(), List.of()), content);

        ArgumentCaptor<SeedPostRow> row = ArgumentCaptor.forClass(SeedPostRow.class);
        verify(mapper).insertSeedPost(row.capture());
        assertThat(row.getValue().imgUrlsJson()).isEqualTo("[]");
        assertThat(firstHash).isNotBlank().isEqualTo(secondHash);

        when(mapper.findIdentity(NAMESPACE, "POST", "post-one"))
                .thenReturn(identity("POST", "post-one", 101L, firstHash));
        when(mapper.findPostStateById(101L))
                .thenReturn(new PostState(101L, 42L, "[]", "posts/post-one.md"));

        WriteResult rerun = writer.write(
                pack(List.of(account("author")), List.of(noMedia), List.of(), List.of()), content);

        verify(mapper, org.mockito.Mockito.times(1)).insertSeedPost(any());
        verify(mapper, never()).updateSeedPostById(any());
        assertThat(rerun.changedPostIds()).isEmpty();
    }

    @Test
    void givenLegacyAndNestedComments_whenWrite_thenReusesLegacyIdAndResolvesParentFirst() {
        stubAccount("author", 42L);
        stubAccount("reader", 43L);
        stubPost("post-one", 99L, 42L);
        when(mapper.findIdentity(NAMESPACE, "COMMENT", "root-comment")).thenReturn(null);
        when(mapper.findLegacyCommentId(99L, 43L, 0)).thenReturn(700L);
        when(mapper.findIdentity(NAMESPACE, "COMMENT", "reply-comment")).thenReturn(null);
        when(idGenerator.nextId()).thenReturn(701L);
        SeedCommentDefinition reply = new SeedCommentDefinition(
                "reply-comment", null, "post-one", "author", "root-comment", "reply", Instant.parse("2026-06-02T00:00:00Z"));
        SeedCommentDefinition root = new SeedCommentDefinition(
                "root-comment", 0, "post-one", "reader", null, "root", Instant.parse("2026-06-01T00:00:00Z"));

        writer.write(pack(List.of(account("author"), account("reader")), List.of(post("post-one", "author")),
                List.of(reply, root), List.of()), published("post-one"));

        ArgumentCaptor<SeedCommentRow> rows = ArgumentCaptor.forClass(SeedCommentRow.class);
        verify(mapper, org.mockito.Mockito.times(2)).upsertSeedComment(rows.capture());
        assertThat(rows.getAllValues()).extracting(SeedCommentRow::id).containsExactly(701L, 700L);
        assertThat(rows.getAllValues().getFirst().parentId()).isEqualTo(700L);
        verify(mapper).upsertIdentity(identity("COMMENT", "root-comment", 700L, null));
        verify(mapper).upsertIdentity(identity("COMMENT", "reply-comment", 701L, null));
    }

    @Test
    void givenRealUserComment_whenWrite_thenNeverDeletesOrUpdatesIt() {
        stubAccount("author", 42L);
        stubPost("post-one", 99L, 42L);

        writer.write(pack(List.of(account("author")), List.of(post("post-one", "author")),
                List.of(), List.of()), published("post-one"));

        verify(mapper).deactivateSeedCommentsExcept(NAMESPACE, Set.of());
    }

    @Test
    void givenDeclaredFollow_whenWrite_thenUpsertsBothSidesAndDeactivatesOnlySeedPairs() {
        stubAccount("author", 42L);
        stubAccount("reader", 43L);
        when(mapper.findIdentity(NAMESPACE, "FOLLOW", "author-reader")).thenReturn(null);
        when(mapper.findFollowState(42L, 43L)).thenReturn(new FollowState(801L, 802L));
        when(mapper.findFollowingById(801L)).thenReturn(new FollowingState(801L, 42L, 43L));
        when(mapper.findFollowerById(802L)).thenReturn(new FollowerState(802L, 42L, 43L));
        SeedFollowDefinition follow = new SeedFollowDefinition(
                "author-reader", "author", "reader", Instant.parse("2026-06-03T00:00:00Z"));

        writer.write(pack(List.of(account("author"), account("reader")), List.of(), List.of(), List.of(follow)),
                emptyPublished());

        verify(mapper).upsertFollowing(new SeedFollowRow(801L, 42L, 43L, follow.createdAt()));
        verify(mapper).upsertFollower(new SeedFollowRow(802L, 42L, 43L, follow.createdAt()));
        verify(mapper).deactivateSeedFollowingExcept(eq(Set.of(42L, 43L)), anyList());
        verify(mapper).deactivateSeedFollowerExcept(eq(Set.of(42L, 43L)), anyList());
    }

    @Test
    void givenFollowIdentityPointsToDifferentPair_whenWrite_thenFailsBeforeRelationMutation() {
        stubAccount("author", 42L);
        stubAccount("reader", 43L);
        when(mapper.findIdentity(NAMESPACE, "FOLLOW", "author-reader"))
                .thenReturn(identity("FOLLOW", "author-reader", 801L, null));
        when(mapper.findFollowingById(801L)).thenReturn(new FollowingState(801L, 42L, 999L));
        SeedFollowDefinition follow = new SeedFollowDefinition(
                "author-reader", "author", "reader", Instant.parse("2026-06-03T00:00:00Z"));

        assertThatThrownBy(() -> writer.write(
                pack(List.of(account("author"), account("reader")), List.of(), List.of(), List.of(follow)),
                emptyPublished()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different account pair");

        verify(mapper, never()).upsertFollowing(any());
        verify(mapper, never()).upsertFollower(any());
    }

    @Test
    void givenFollowIdentityMatchesStoredPair_whenWrite_thenReusesBothRelationIds() {
        stubAccount("author", 42L);
        stubAccount("reader", 43L);
        when(mapper.findIdentity(NAMESPACE, "FOLLOW", "author-reader"))
                .thenReturn(identity("FOLLOW", "author-reader", 801L, null));
        when(mapper.findFollowingById(801L)).thenReturn(new FollowingState(801L, 42L, 43L));
        when(mapper.findFollowState(42L, 43L)).thenReturn(new FollowState(801L, 802L));
        when(mapper.findFollowerById(802L)).thenReturn(new FollowerState(802L, 42L, 43L));
        SeedFollowDefinition follow = new SeedFollowDefinition(
                "author-reader", "author", "reader", Instant.parse("2026-06-03T00:00:00Z"));

        writer.write(pack(List.of(account("author"), account("reader")), List.of(), List.of(), List.of(follow)),
                emptyPublished());

        verify(mapper).upsertFollowing(new SeedFollowRow(801L, 42L, 43L, follow.createdAt()));
        verify(mapper).upsertFollower(new SeedFollowRow(802L, 42L, 43L, follow.createdAt()));
    }

    @Test
    void givenFollowerRowRedirected_whenWrite_thenFailsBeforeRelationMutation() {
        stubAccount("author", 42L);
        stubAccount("reader", 43L);
        when(mapper.findIdentity(NAMESPACE, "FOLLOW", "author-reader")).thenReturn(null);
        when(mapper.findFollowState(42L, 43L)).thenReturn(new FollowState(801L, 802L));
        when(mapper.findFollowingById(801L)).thenReturn(new FollowingState(801L, 42L, 43L));
        when(mapper.findFollowerById(802L)).thenReturn(new FollowerState(802L, 999L, 43L));
        SeedFollowDefinition follow = new SeedFollowDefinition(
                "author-reader", "author", "reader", Instant.parse("2026-06-03T00:00:00Z"));

        assertThatThrownBy(() -> writer.write(
                pack(List.of(account("author"), account("reader")), List.of(), List.of(), List.of(follow)),
                emptyPublished()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different account pair");

        verify(mapper, never()).upsertFollowing(any());
        verify(mapper, never()).upsertFollower(any());
    }

    @Test
    void givenFollowIdentityButFollowingRowMissing_whenWrite_thenRebuildsWithIdentityId() {
        stubAccount("author", 42L);
        stubAccount("reader", 43L);
        when(mapper.findIdentity(NAMESPACE, "FOLLOW", "author-reader"))
                .thenReturn(identity("FOLLOW", "author-reader", 801L, null));
        when(mapper.findFollowingById(801L)).thenReturn(null);
        when(mapper.findFollowState(42L, 43L)).thenReturn(new FollowState(null, null));
        when(idGenerator.nextId()).thenReturn(802L);
        SeedFollowDefinition follow = new SeedFollowDefinition(
                "author-reader", "author", "reader", Instant.parse("2026-06-03T00:00:00Z"));

        writer.write(pack(List.of(account("author"), account("reader")), List.of(), List.of(), List.of(follow)),
                emptyPublished());

        verify(mapper).upsertFollowing(new SeedFollowRow(801L, 42L, 43L, follow.createdAt()));
        verify(mapper).upsertFollower(new SeedFollowRow(802L, 42L, 43L, follow.createdAt()));
    }

    @Test
    void givenFollowIdentityButFollowerRowMissing_whenWrite_thenRebuildsFollowerMirror() {
        stubAccount("author", 42L);
        stubAccount("reader", 43L);
        when(mapper.findIdentity(NAMESPACE, "FOLLOW", "author-reader"))
                .thenReturn(identity("FOLLOW", "author-reader", 801L, null));
        when(mapper.findFollowingById(801L)).thenReturn(new FollowingState(801L, 42L, 43L));
        when(mapper.findFollowState(42L, 43L)).thenReturn(new FollowState(801L, null));
        when(idGenerator.nextId()).thenReturn(802L);
        SeedFollowDefinition follow = new SeedFollowDefinition(
                "author-reader", "author", "reader", Instant.parse("2026-06-03T00:00:00Z"));

        writer.write(pack(List.of(account("author"), account("reader")), List.of(), List.of(), List.of(follow)),
                emptyPublished());

        verify(mapper).upsertFollowing(new SeedFollowRow(801L, 42L, 43L, follow.createdAt()));
        verify(mapper).upsertFollower(new SeedFollowRow(802L, 42L, 43L, follow.createdAt()));
    }

    @Test
    void givenInvalidCommentGraph_whenWrite_thenFailsBeforeAnyMapperMutation() {
        SeedCommentDefinition first = new SeedCommentDefinition(
                "first", null, "post-one", "author", "second", "one", Instant.parse("2026-06-02T00:00:00Z"));
        SeedCommentDefinition second = new SeedCommentDefinition(
                "second", null, "post-two", "reader", "first", "two", Instant.parse("2026-06-03T00:00:00Z"));

        assertThatThrownBy(() -> writer.write(
                pack(List.of(), List.of(), List.of(first, second), List.of()), emptyPublished()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("comment parent");

        verifyNoInteractions(mapper, tagService, idGenerator);
    }

    @Test
    void givenMapperFailure_whenWrite_thenPropagatesInsideTransactionalBoundary() throws Exception {
        Method write = ContentPackDatabaseWriter.class.getMethod("write", ContentPack.class, PublishedContent.class);
        assertThat(write.getAnnotation(Transactional.class)).isNotNull();
        stubAccount("author", 42L);
        when(mapper.updateSeedUserById(any())).thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> writer.write(pack(List.of(account("author")), List.of(), List.of(), List.of()),
                emptyPublished()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database unavailable");

        verify(mapper, never()).deactivateSeedFollowingExcept(any(), anyList());
    }

    @Test
    void mapperXml_parsesAndUsesOnlyParameterizedValuesWithEmptyCollectionGuards() throws Exception {
        Path mapperPath = Path.of("src/main/resources/mapper/ContentPackMapper.xml");
        String xml = Files.readString(mapperPath);
        assertThat(xml).doesNotContain("${");
        assertThat(xml).contains("<otherwise>", "AND 1 = 0", "#{pair.fromUserId}", "#{ordinal}");

        Configuration configuration = new Configuration();
        try (var input = Files.newInputStream(mapperPath)) {
            new XMLMapperBuilder(input, configuration, mapperPath.toString(), configuration.getSqlFragments()).parse();
        }
        assertThat(configuration.hasStatement(
                "com.chtholly.seed.contentpack.ContentPackMapper.deactivateSeedFollowingExcept")).isTrue();
        assertThat(configuration.hasStatement(
                "com.chtholly.seed.contentpack.ContentPackMapper.snapshotSeedInteractions")).isTrue();
        assertThat(configuration.hasStatement(
                "com.chtholly.seed.contentpack.ContentPackMapper.findFollowingById")).isTrue();
        assertThat(configuration.hasStatement(
                "com.chtholly.seed.contentpack.ContentPackMapper.findFollowerById")).isTrue();
    }

    private void stubAccount(String seedKey, long id) {
        when(mapper.findIdentity(NAMESPACE, "ACCOUNT", seedKey))
                .thenReturn(identity("ACCOUNT", seedKey, id, null));
        when(mapper.seedUserExistsById(id)).thenReturn(true);
    }

    private void stubPost(String seedKey, long id, long creatorId) {
        when(mapper.findIdentity(NAMESPACE, "POST", seedKey))
                .thenReturn(identity("POST", seedKey, id, "old-hash"));
        when(mapper.findPostStateById(id))
                .thenReturn(new PostState(id, creatorId, "[\"old\"]", "old.md"));
    }

    private SeedContentIdentity identity(String type, String key, long id, String hash) {
        return new SeedContentIdentity(NAMESPACE, type, key, id, VERSION, hash, null);
    }

    private SeedAccountDefinition account(String seedKey) {
        String handle = seedKey.equals("author") ? "shirokuma_on" : "savepoint_404";
        return new SeedAccountDefinition(seedKey, seedKey + "-old", seedKey, handle, "bio", "avatar-" + seedKey,
                null, null, null, List.of("seed"), null);
    }

    private SeedPostDefinition post(String seedKey, String author) {
        return new SeedPostDefinition(seedKey, "legacy-" + seedKey, author, "title", "public-" + seedKey,
                "description", "backend", List.of("Java", "MySQL"), Instant.parse("2026-06-01T00:00:00Z"),
                "posts/" + seedKey + ".md", "cover", List.of("inline"), null, "body");
    }

    private ContentPack pack(List<SeedAccountDefinition> accounts, List<SeedPostDefinition> posts,
                             List<SeedCommentDefinition> comments, List<SeedFollowDefinition> follows) {
        return new ContentPack(Path.of("."),
                new ContentPackManifest(VERSION, NAMESPACE, "complete", accounts.size(), posts.size(), Map.of()),
                accounts, Map.of(), posts, comments, follows, List.of(), List.of());
    }

    private PublishedContent published(String postKey) {
        Map<String, PublishedAsset> assets = Map.of(
                "avatar-author", asset("avatar-author", "/media/avatar-author.webp"),
                "avatar-reader", asset("avatar-reader", "/media/avatar-reader.webp"),
                "cover", asset("cover", "/media/cover.webp"),
                "inline", asset("inline", "/media/inline.webp"));
        return new PublishedContent(assets, Map.of(postKey,
                new PublishedAsset(postKey, "posts/" + postKey + ".md", "/posts/" + postKey + ".md",
                        "b".repeat(64), "text/markdown", 4L, false)), List.of(), NAMESPACE, "lease");
    }

    private PublishedContent emptyPublished() {
        return new PublishedContent(Map.of(
                "avatar-author", asset("avatar-author", "/media/avatar-author.webp"),
                "avatar-reader", asset("avatar-reader", "/media/avatar-reader.webp")),
                Map.of(), List.of(), NAMESPACE, "lease");
    }

    private PublishedAsset asset(String key, String url) {
        return new PublishedAsset(key, "objects/" + key, url, "a".repeat(64), "image/webp", 10L, false);
    }
}
