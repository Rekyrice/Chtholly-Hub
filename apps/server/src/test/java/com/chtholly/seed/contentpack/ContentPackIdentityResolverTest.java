package com.chtholly.seed.contentpack;

import com.chtholly.post.id.SnowflakeIdGenerator;
import com.chtholly.seed.contentpack.model.SeedAccountDefinition;
import com.chtholly.seed.contentpack.model.SeedContentIdentity;
import com.chtholly.seed.contentpack.model.SeedPostDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentPackIdentityResolverTest {

    private static final String NAMESPACE = "launch-community";
    private static final String VERSION = "content-v2";

    @Mock
    private ContentPackMapper mapper;

    @Mock
    private SnowflakeIdGenerator idGenerator;

    private ContentPackIdentityResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ContentPackIdentityResolver(NAMESPACE, mapper, idGenerator);
    }

    @Test
    void given_existingAccountIdentity_when_resolve_then_reusesEntityId() {
        SeedContentIdentity existing = new SeedContentIdentity(
                NAMESPACE, "ACCOUNT", "night-coder", 42L,
                "content-v1", "old-hash", "{\"source\":\"v1\"}");
        when(mapper.findIdentity(NAMESPACE, "ACCOUNT", "night-coder"))
                .thenReturn(existing);

        assertThat(resolver.resolveAccountId(account(), VERSION)).isEqualTo(42L);

        verify(idGenerator, never()).nextId();
        verify(mapper, never()).findLegacyUserId(any());
        verify(mapper).upsertIdentity(existing);
    }

    @Test
    void given_legacyAccountWithoutIdentity_when_resolve_then_backfillsMapping() {
        when(mapper.findLegacyUserId("old-night-coder@seed.chtholly.invalid")).thenReturn(77L);

        assertThat(resolver.resolveAccountId(account(), VERSION)).isEqualTo(77L);

        verify(mapper).upsertIdentity(identity("ACCOUNT", "night-coder", 77L));
        verify(idGenerator, never()).nextId();
    }

    @Test
    void given_existingPostIdentity_when_resolve_then_reusesEntityIdWithoutSlugLookup() {
        when(mapper.findIdentity(NAMESPACE, "POST", "java-clock-debug"))
                .thenReturn(identity("POST", "java-clock-debug", 88L));

        assertThat(resolver.resolvePostId(post(), VERSION)).isEqualTo(88L);

        verify(mapper, never()).findLegacyPostId(any());
        verify(idGenerator, never()).nextId();
    }

    @Test
    void given_legacyPostWithoutIdentity_when_resolve_then_backfillsMapping() {
        when(mapper.findLegacyPostId("legacy-java-clock")).thenReturn(99L);

        assertThat(resolver.resolvePostId(post(), VERSION)).isEqualTo(99L);

        verify(mapper).upsertIdentity(identity("POST", "java-clock-debug", 99L));
        verify(idGenerator, never()).nextId();
    }

    @Test
    void given_newPostWithoutLegacyMatch_when_resolve_then_generatesStableIdMapping() {
        when(mapper.findLegacyPostId("legacy-java-clock")).thenReturn(null);
        doReturn(1234L).when(idGenerator).nextId();

        assertThat(resolver.resolvePostId(post(), VERSION)).isEqualTo(1234L);

        verify(mapper).upsertIdentity(identity("POST", "java-clock-debug", 1234L));
    }

    @Test
    void given_generatedIdentityLosesConcurrentInsert_when_resolve_then_returnsWinnerId() {
        SeedContentIdentity winner = identity("POST", "java-clock-debug", 5678L);
        when(mapper.findIdentity(NAMESPACE, "POST", "java-clock-debug"))
                .thenReturn(null, winner);
        when(mapper.findLegacyPostId("legacy-java-clock")).thenReturn(null);
        doReturn(1234L).when(idGenerator).nextId();
        RuntimeException duplicate = new RuntimeException("duplicate key");
        doThrow(duplicate).when(mapper)
                .upsertIdentity(identity("POST", "java-clock-debug", 1234L));

        assertThat(resolver.resolvePostId(post(), VERSION)).isEqualTo(5678L);
    }

    @Test
    void given_generatedIdentityInsertFailsWithoutWinner_when_resolve_then_rethrowsOriginalFailure() {
        when(mapper.findIdentity(NAMESPACE, "POST", "java-clock-debug"))
                .thenReturn(null, null);
        when(mapper.findLegacyPostId("legacy-java-clock")).thenReturn(null);
        doReturn(1234L).when(idGenerator).nextId();
        RuntimeException duplicate = new RuntimeException("duplicate key");
        doThrow(duplicate).when(mapper)
                .upsertIdentity(identity("POST", "java-clock-debug", 1234L));

        assertThatThrownBy(() -> resolver.resolvePostId(post(), VERSION))
                .isSameAs(duplicate);
    }

    @Test
    void given_legacyEntityConflictsWithAnotherSeedKey_when_resolve_then_doesNotTreatItAsRaceWinner() {
        when(mapper.findLegacyPostId("legacy-java-clock")).thenReturn(99L);
        RuntimeException conflict = new RuntimeException("entity ID already belongs to another seed key");
        doThrow(conflict).when(mapper)
                .upsertIdentity(identity("POST", "java-clock-debug", 99L));

        assertThatThrownBy(() -> resolver.resolvePostId(post(), VERSION))
                .isSameAs(conflict);

        verify(mapper).findIdentity(NAMESPACE, "POST", "java-clock-debug");
    }

    @Test
    void updateIdentityHash_reportsMissingIdentityToCaller() {
        when(mapper.updateIdentityHash(
                NAMESPACE, "POST", "java-clock-debug", VERSION, "new-hash", "{}"))
                .thenReturn(0);

        int affected = mapper.updateIdentityHash(
                NAMESPACE, "POST", "java-clock-debug", VERSION, "new-hash", "{}");

        assertThat(affected).isZero();
    }

    @Test
    void given_identityWithWrongType_when_resolve_then_failsExplicitly() {
        SeedContentIdentity corrupt = new SeedContentIdentity(
                NAMESPACE, "POST", "night-coder", 42L, "content-v1", null, null);
        when(mapper.findIdentity(NAMESPACE, "ACCOUNT", "night-coder")).thenReturn(corrupt);

        assertThatThrownBy(() -> resolver.resolveAccountId(account(), VERSION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACCOUNT")
                .hasMessageContaining("night-coder");

        verify(mapper, never()).upsertIdentity(any());
    }

    @Test
    void mapperXml_usesOnlyParameterizedValues() throws Exception {
        Path mapperPath = Path.of("src/main/resources/mapper/ContentPackMapper.xml");
        String xml = Files.readString(mapperPath);

        assertThat(xml).doesNotContain("${");
        assertThat(xml).contains("#{namespace}", "#{entityType}", "#{seedKey}");
        assertThat(xml).contains("AND entity_id = VALUES(entity_id)", "NULL");
        assertThat(xml).contains("column=\"entity_id\" javaType=\"_long\"");
        assertThat(xml).doesNotContain("javaType=\"long\"");

        Configuration configuration = new Configuration();
        try (var input = Files.newInputStream(mapperPath)) {
            new XMLMapperBuilder(input, configuration, mapperPath.toString(), configuration.getSqlFragments()).parse();
        }
        assertThat(configuration.hasStatement(
                "com.chtholly.seed.contentpack.ContentPackMapper.findIdentity")).isTrue();
    }

    private SeedContentIdentity identity(String type, String seedKey, long entityId) {
        return new SeedContentIdentity(NAMESPACE, type, seedKey, entityId, VERSION, null, null);
    }

    private SeedAccountDefinition account() {
        return new SeedAccountDefinition(
                "night-coder", "old-night-coder", "白熊没关机", "shirokuma_on", "bio", "avatar",
                null, null, null, List.of(), null);
    }

    private SeedPostDefinition post() {
        return new SeedPostDefinition(
                "java-clock-debug", "legacy-java-clock", "night-coder", "title", "new-slug", "desc",
                "backend", List.of("Java"), Instant.parse("2026-07-01T00:00:00Z"), "post.md", "cover",
                List.of(), null, "body");
    }
}
