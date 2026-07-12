# Seed Content Pack Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current low-quality Seed community with 8 natural-looking accounts, 40 real-source posts, and reproducible non-uniform interactions without changing existing Seed user IDs or the IDs of the 32 existing Seed posts.

**Architecture:** Tasks 1–9 built and verified the reusable content-pack infrastructure. The 2026-07-12 revision below replaces the rejected `content-v2` editorial work with a real-source `content-v3` package, structured provenance cards, optional post media, and a three-post user review gate. MySQL remains the runtime source of truth, StorageService owns stable media URLs, and SearchIndexService is synchronized after database commit.

**Tech Stack:** Java 21, Spring Boot 3.2.4, MyBatis XML, Jackson YAML, MySQL, Redis, Elasticsearch, existing StorageService, Node.js with Sharp for deterministic image preprocessing, JUnit 5, Mockito, Node built-in test runner.

---

## Scope and file map

### Content pack files

- Create: `content/seed/content-v2/manifest.yml`
- Create: `content/seed/content-v2/accounts.yml`
- Create: `content/seed/content-v2/assets.yml`
- Create: `content/seed/content-v2/posts.yml`
- Create: `content/seed/content-v2/interactions.yml`
- Create: `content/seed/content-v2/posts/*.md`
- Create: `content/seed/content-v2/assets/avatars/*.webp`
- Create: `content/seed/content-v2/assets/covers/*.webp`
- Create: `content/seed/content-v2/assets/inline/*.webp`

### Backend content-pack boundary

- Create: `apps/server/src/main/java/com/chtholly/seed/contentpack/model/ContentPack.java`
- Create: `apps/server/src/main/java/com/chtholly/seed/contentpack/model/ContentPackManifest.java`
- Create: `apps/server/src/main/java/com/chtholly/seed/contentpack/model/SeedAccountDefinition.java`
- Create: `apps/server/src/main/java/com/chtholly/seed/contentpack/model/SeedAssetDefinition.java`
- Create: `apps/server/src/main/java/com/chtholly/seed/contentpack/model/SeedPostDefinition.java`
- Create: `apps/server/src/main/java/com/chtholly/seed/contentpack/model/SeedCommentDefinition.java`
- Create: `apps/server/src/main/java/com/chtholly/seed/contentpack/model/SeedFollowDefinition.java`
- Create: `apps/server/src/main/java/com/chtholly/seed/contentpack/model/SeedReactionDefinition.java`
- Create: `apps/server/src/main/java/com/chtholly/seed/contentpack/model/SeedViewDefinition.java`
- Create: `apps/server/src/main/java/com/chtholly/seed/contentpack/model/SeedContentIdentity.java`
- Create: `apps/server/src/main/java/com/chtholly/seed/contentpack/model/ContentPackImportReport.java`
- Create: `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackLoader.java`
- Create: `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackValidator.java`
- Create: `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackQualityGate.java`
- Create: `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackMediaPublisher.java`
- Create: `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackSnapshotWriter.java`
- Create: `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackIdentityResolver.java`
- Create: `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackDatabaseWriter.java`
- Create: `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackReactionApplier.java`
- Create: `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackImportService.java`
- Create: `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackMapper.java`
- Create: `apps/server/src/main/resources/mapper/ContentPackMapper.xml`

Each file has one responsibility: models contain data only; the loader handles filesystem/YAML; validation has no persistence; media publishing only resolves stable URLs; identity resolution owns seedKey-to-ID mapping; the writer owns transactional MySQL mutations; the import service owns stage ordering and reports.

### Database and configuration

- Create: `apps/server/db/migration/V22__seed_content_identity.sql`
- Modify: `apps/server/db/schema.sql`
- Modify: `apps/server/pom.xml`
- Modify: `apps/server/src/main/resources/application.yml`
- Modify: `apps/server/src/main/java/com/chtholly/seed/SeedProperties.java`
- Modify: `apps/server/src/main/java/com/chtholly/seed/SeedRunMode.java`
- Modify: `apps/server/src/main/java/com/chtholly/seed/SeedRunner.java`
- Modify: `apps/server/src/main/java/com/chtholly/storage/StorageService.java`
- Modify: `apps/server/src/main/java/com/chtholly/storage/LocalFileStorageService.java`
- Modify: `apps/server/src/main/java/com/chtholly/storage/OssStorageService.java`
- Modify: `scripts/dev/run-seed.ps1`
- Modify: `.gitignore`

### Media tooling

- Create: `apps/web/scripts/seed/prepare-content-media.mjs`
- Create: `apps/web/scripts/seed/prepare-content-media.test.mjs`
- Modify: `apps/web/package.json`
- Modify: `apps/web/package-lock.json`

Use the already resolved Sharp version `0.34.5`; add it as a direct development dependency along with a direct YAML parser dependency so the media tool does not rely on Next.js transitive packages.

### Tests

- Create: `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackLoaderTest.java`
- Create: `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackValidatorTest.java`
- Create: `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackQualityGateTest.java`
- Create: `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackIdentityResolverTest.java`
- Create: `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackMediaPublisherTest.java`
- Create: `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackDatabaseWriterTest.java`
- Create: `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackReactionApplierTest.java`
- Create: `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackImportServiceTest.java`
- Create: `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackSnapshotWriterTest.java`
- Create: `apps/server/src/test/java/com/chtholly/search/index/SearchIndexServiceTest.java`
- Modify: `apps/server/src/test/java/com/chtholly/seed/SeedOrchestratorTest.java`
- Create: `apps/server/src/test/java/com/chtholly/seed/SeedRunnerTest.java`
- Create: `apps/server/src/test/resources/content-pack/valid/**`
- Create: `apps/server/src/test/resources/content-pack/invalid/**`

## Task 1: Define the content-pack model and YAML loader

**Files:**
- Modify: `apps/server/pom.xml`
- Create: `apps/server/src/main/java/com/chtholly/seed/contentpack/model/*.java`
- Create: `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackLoader.java`
- Create: `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackLoaderTest.java`
- Create: `apps/server/src/test/resources/content-pack/valid/**`

- [ ] **Step 1: Write the failing loader test**

Create a minimal fixture with one account, asset, post Markdown, comment, follow and reaction. The test must prove path resolution and all five YAML files load together:

```java
@Test
void given_validPack_when_load_then_resolvesMarkdownInsideRoot() {
    Path root = fixture("content-pack/valid");
    ContentPack pack = new ContentPackLoader(yamlMapper()).load(root);

    assertThat(pack.manifest().version()).isEqualTo("content-v2");
    assertThat(pack.accounts()).extracting(SeedAccountDefinition::seedKey)
            .containsExactly("night-coder");
    assertThat(pack.posts().getFirst().markdown()).contains("一次真实排障");
    assertThat(pack.assets()).containsKey("avatar-night-coder");
    assertThat(pack.comments()).hasSize(1);
    assertThat(pack.follows()).hasSize(1);
    assertThat(pack.reactions()).hasSize(1);
    assertThat(pack.views()).hasSize(1);
}
```

- [ ] **Step 2: Run the loader test and verify it fails**

Run:

```powershell
cd apps/server
mvn -q -Dtest=ContentPackLoaderTest test
```

Expected: compilation failure because `ContentPackLoader` and model records do not exist.

- [ ] **Step 3: Add Jackson YAML and the immutable model records**

Add the Boot-managed dependency:

```xml
<dependency>
    <groupId>com.fasterxml.jackson.dataformat</groupId>
    <artifactId>jackson-dataformat-yaml</artifactId>
</dependency>
```

Use records with collection null-protection. The central record must be:

```java
public record ContentPack(
        Path root,
        ContentPackManifest manifest,
        List<SeedAccountDefinition> accounts,
        Map<String, SeedAssetDefinition> assets,
        List<SeedPostDefinition> posts,
        List<SeedCommentDefinition> comments,
        List<SeedFollowDefinition> follows,
        List<SeedReactionDefinition> reactions,
        List<SeedViewDefinition> views
) {
    public ContentPack {
        accounts = List.copyOf(accounts == null ? List.of() : accounts);
        assets = Map.copyOf(assets == null ? Map.of() : assets);
        posts = List.copyOf(posts == null ? List.of() : posts);
        comments = List.copyOf(comments == null ? List.of() : comments);
        follows = List.copyOf(follows == null ? List.of() : follows);
        reactions = List.copyOf(reactions == null ? List.of() : reactions);
        views = List.copyOf(views == null ? List.of() : views);
    }
}
```

Use these exact minimum fields:

```java
public record ContentPackManifest(
        String version,
        String namespace,
        String stage,
        int expectedAccounts,
        int expectedPosts,
        Map<String, Integer> expectedCategories
) {}

public record SeedAccountDefinition(
        String seedKey, String legacyHandle, String nickname, String handle,
        String bio, String avatarAsset, String gender, LocalDate birthday,
        String school, List<String> tags, AuthorVoice voice
) {}

public record SeedPostDefinition(
        String seedKey, String legacySlug, String authorSeedKey, String title,
        String slug, String description, String category, List<String> tags,
        Instant publishTime, String markdownFile, String coverAsset,
        List<String> inlineAssets, ArticleBrief brief, String markdown
) {}

public record SeedAssetDefinition(
        String key, String source, String sourceUrl, String sourceFile, String file,
        String objectKey, String sha256, String contentType,
        int width, int height, String usage
) {}

public record SeedCommentDefinition(
        String seedKey, Integer legacyOrdinal, String postSeedKey,
        String authorSeedKey, String parentSeedKey, String content, Instant createdAt
) {}

public record SeedFollowDefinition(
        String seedKey, String fromAccountSeedKey, String toAccountSeedKey, Instant createdAt
) {}

public record SeedReactionDefinition(
        String seedKey, String postSeedKey, String accountSeedKey, String type
) {}

public record SeedViewDefinition(
        String seedKey, String postSeedKey, long minimumCount
) {}

public record SeedContentIdentity(
        String namespace, String entityType, String seedKey, long entityId,
        String packVersion, String contentHash, String metadataJson
) {}
```

Define `AuthorVoice` and `ArticleBrief` as nested records in their owning definitions so the public package does not gain shallow one-use types. Keep `ValidationResult`, `QualityGateResult`, `PublishedAsset`, `PublishedContent`, `WriteResult`, `SnapshotRef` and `IndexResult` as nested records in the service that owns each lifecycle; do not create additional shallow files for them.

- [ ] **Step 4: Implement safe loader path resolution**

`ContentPackLoader.load(Path root)` must normalize the root, load the five YAML documents with `ObjectMapper(new YAMLFactory()).findAndRegisterModules()`, and reject any Markdown path escaping the root:

```java
private static Path resolveInside(Path root, String relative) {
    Path resolved = root.resolve(relative).normalize();
    if (!resolved.startsWith(root)) {
        throw new IllegalArgumentException("Content pack path escapes root: " + relative);
    }
    return resolved;
}
```

The loader reads Markdown as UTF-8 and returns definitions copied with the `markdown` field populated.

- [ ] **Step 5: Run loader tests**

Run:

```powershell
cd apps/server
mvn -q -Dtest=ContentPackLoaderTest test
```

Expected: PASS.

- [ ] **Step 6: Commit the loader boundary**

```powershell
git add apps/server/pom.xml apps/server/src/main/java/com/chtholly/seed/contentpack/model apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackLoader.java apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackLoaderTest.java apps/server/src/test/resources/content-pack
git commit -m "feat: 建立版本化内容包读取模型" -m "引入 YAML 内容清单和安全路径解析，为账号、文章、媒体与互动提供不可变输入边界。"
```

## Task 2: Add structural validation and anti-template quality gates

**Files:**
- Create: `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackValidator.java`
- Create: `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackQualityGate.java`
- Create: `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackValidatorTest.java`
- Create: `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackQualityGateTest.java`
- Create: `apps/server/src/test/resources/content-pack/invalid/**`

- [ ] **Step 1: Write failing validation tests**

Cover exact failure messages:

```java
@Test
void given_duplicateSeedKey_when_validate_then_rejectsBeforeWrites() {
    ContentPack pack = fixtureWithDuplicatePostKey("post-01");
    assertThatThrownBy(() -> validator.validate(pack))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("duplicate post seedKey: post-01");
}

@Test
void given_missingCover_when_validate_then_namesPostAndAsset() {
    ContentPack pack = fixtureWithMissingCover("anime-01", "cover-anime-01");
    assertThatThrownBy(() -> validator.validate(pack))
            .hasMessageContaining("anime-01")
            .hasMessageContaining("cover-anime-01");
}
```

Also test duplicate handle, duplicate slug, missing author, missing Markdown, missing inline asset, wrong expected counts, invalid reaction type, self-follow, and a path outside root.

- [ ] **Step 2: Write failing quality-gate tests**

```java
@Test
void given_boilerplateAndDuplicateLead_when_audit_then_reportsBothPosts() {
    ContentPack pack = packWithBodies(
            Map.of("a", "在当今快速发展的时代，首先要理解系统。总之值得一提。",
                   "b", "在当今快速发展的时代，首先要理解系统。然后再处理。"));
    QualityGateResult result = gate.audit(pack);

    assertThat(result.errors()).anyMatch(value -> value.contains("boilerplate"));
    assertThat(result.errors()).anyMatch(value -> value.contains("duplicate lead"));
}
```

- [ ] **Step 3: Implement structural validation**

`ContentPackValidator.validate` must enforce:

- manifest version and namespace are nonblank;
- stage is exactly `review` or `complete`;
- exact account/post/category counts match manifest;
- all seedKeys are unique within each entity type;
- public handles and slugs are unique;
- every post author exists;
- every cover/inline asset exists;
- every Markdown file is nonblank;
- descriptions are 10–50 characters because the database column is `VARCHAR(50)`;
- account handles match `[A-Za-z0-9_]{3,64}`;
- reactions are only `like` or `fav`;
- every view baseline references an existing post and is nonnegative;
- follows are not self-follows;
- all timestamps are present and interaction timestamps occur after the relevant article publication.

Return a `ValidationResult` containing deterministic ordered warnings; throw only after collecting all errors so one dry-run reports every defect.

- [ ] **Step 4: Implement deterministic quality checks**

`ContentPackQualityGate` must not require an LLM. It checks:

- banned phrases: `在当今.*时代`, `首先.*其次.*最后`, `总的来说`, `值得一提`, `让我们`;
- exact duplicate first non-heading paragraph;
- 5-gram Jaccard similarity above `0.38`;
- identical H2/H3 heading sequences;
- repeated ending paragraphs;
- no concrete anchor from the Brief appears in the Markdown;
- body shorter than 600 Chinese characters or longer than 2,600 characters, except when `brief.format` is `short-note`.

Warnings do not block a dry-run; errors block a formal import.

- [ ] **Step 5: Run targeted tests**

Run:

```powershell
cd apps/server
mvn -q '-Dtest=ContentPackValidatorTest,ContentPackQualityGateTest' test
```

Expected: PASS.

- [ ] **Step 6: Commit validation**

```powershell
git add apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackValidator.java apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackQualityGate.java apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackValidatorTest.java apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackQualityGateTest.java apps/server/src/test/resources/content-pack/invalid
git commit -m "feat: 增加内容包结构与文风质量门禁" -m "在任何持久化前检查引用完整性、内容数量、套话、重复开头和跨文相似度。"
```

## Task 3: Persist immutable seed identities

**Files:**
- Create: `apps/server/db/migration/V22__seed_content_identity.sql`
- Modify: `apps/server/db/schema.sql`
- Create: `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackMapper.java`
- Create: `apps/server/src/main/resources/mapper/ContentPackMapper.xml`
- Create: `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackIdentityResolver.java`
- Create: `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackIdentityResolverTest.java`

- [ ] **Step 1: Write failing identity-resolution tests**

```java
@Test
void given_existingIdentity_when_resolve_then_reusesEntityId() {
    when(mapper.findIdentity("launch-community", "ACCOUNT", "night-coder"))
            .thenReturn(new SeedContentIdentity(
                    "launch-community", "ACCOUNT", "night-coder", 42L,
                    "content-v1", "old", null));
    assertThat(resolver.resolveAccountId(definition, "content-v2")).isEqualTo(42L);
    verify(idGenerator, never()).nextId();
}

@Test
void given_legacyAccountWithoutIdentity_when_resolve_then_backfillsMapping() {
    when(mapper.findLegacyUserId("night-coder@seed.chtholly.invalid")).thenReturn(77L);
    assertThat(resolver.resolveAccountId(definition, "content-v2")).isEqualTo(77L);
    verify(mapper).upsertIdentity(argThat(row -> row.entityId() == 77L));
}
```

Add equivalent tests for legacy post slug reuse and new post ID generation.

- [ ] **Step 2: Add the identity migration**

Use a stable namespace independent of pack version:

```sql
CREATE TABLE IF NOT EXISTS seed_content_identity (
    namespace VARCHAR(64) NOT NULL,
    entity_type VARCHAR(16) NOT NULL,
    seed_key VARCHAR(128) NOT NULL,
    entity_id BIGINT UNSIGNED NOT NULL,
    pack_version VARCHAR(64) NOT NULL,
    content_hash CHAR(64) NULL,
    metadata JSON NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (namespace, entity_type, seed_key),
    UNIQUE KEY uk_seed_identity_entity (entity_type, entity_id),
    KEY ix_seed_identity_version (pack_version, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

Copy the same table into the consolidated `schema.sql`. Do not add an auto-increment ID.

- [ ] **Step 3: Add parameterized mapper methods**

`ContentPackMapper` must expose:

```java
SeedContentIdentity findIdentity(String namespace, String entityType, String seedKey);
Long findLegacyUserId(String email);
Long findLegacyPostId(String slug);
void upsertIdentity(SeedContentIdentity row);
void updateIdentityHash(String namespace, String entityType, String seedKey,
                        String packVersion, String contentHash, String metadataJson);
```

Every XML value uses `#{...}`; add a test assertion that `ContentPackMapper.xml` contains no `${`.

- [ ] **Step 4: Implement the resolver**

Resolution order is:

1. existing `seed_content_identity`;
2. legacy account email or legacy post slug;
3. `SnowflakeIdGenerator.nextId()`.

Immediately upsert an identity after resolving. Public handle and slug never participate after the initial legacy lookup.

- [ ] **Step 5: Run migration and resolver checks**

Run:

```powershell
cd apps/server
mvn -q -Dtest=ContentPackIdentityResolverTest test
cd ../..
.\scripts\dev\apply-migrations.ps1
```

Expected: test PASS and migration reports V22 applied or already present.

- [ ] **Step 6: Commit identity persistence**

```powershell
git add apps/server/db/migration/V22__seed_content_identity.sql apps/server/db/schema.sql apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackMapper.java apps/server/src/main/resources/mapper/ContentPackMapper.xml apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackIdentityResolver.java apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackIdentityResolverTest.java
git commit -m "feat: 持久化内容包稳定身份映射" -m "使用 namespace、entityType 与 seedKey 复用旧用户和文章 ID，并支持公开 handle 与 slug 改名。"
```

## Task 4: Build deterministic media preparation tooling

**Files:**
- Modify: `apps/web/package.json`
- Modify: `apps/web/package-lock.json`
- Create: `apps/web/scripts/seed/prepare-content-media.mjs`
- Create: `apps/web/scripts/seed/prepare-content-media.test.mjs`
- Modify: `.gitignore`

- [ ] **Step 1: Add project-local temporary directories to Git ignore**

Add:

```gitignore
.codex-tmp/
.superpowers/
```

All downloads, image-generation intermediates, reports and backup snapshots go under `.codex-tmp/seed-content-v2/`, never under the user home directory or C: drive.

- [ ] **Step 2: Install direct authoring dependencies**

Run:

```powershell
cd apps/web
npm install --save-dev sharp@0.34.5 yaml@2.8.1
```

Expected: `package.json` directly lists `sharp` and `yaml`; lockfile updates without unrelated upgrades.

- [ ] **Step 3: Write the failing Node media test**

Use Node's built-in test runner and generate a source SVG in the test temp directory:

```javascript
test("prepares a square WebP avatar and records sha256", async () => {
  const result = await prepareOne({
    input: fixtureSvg,
    output: outputWebp,
    width: 512,
    height: 512,
    fit: "cover",
  });
  const meta = await sharp(outputWebp).metadata();
  assert.equal(meta.format, "webp");
  assert.equal(meta.width, 512);
  assert.equal(meta.height, 512);
  assert.match(result.sha256, /^[a-f0-9]{64}$/);
});
```

- [ ] **Step 4: Run the test and verify it fails**

Run:

```powershell
cd apps/web
node --test scripts/seed/prepare-content-media.test.mjs
```

Expected: FAIL because the script does not exist.

- [ ] **Step 5: Implement media preparation**

The script must:

- load `assets.yml` using `yaml`;
- resolve every `sourceFile` from the project-local `.codex-tmp/seed-content-v2/sources` directory or the approved local `_incoming/pic` directory;
- treat `sourceUrl` as provenance metadata only, never as a runtime hotlink;
- reject source files above 15 MiB and non-image inputs;
- crop avatars to 512×512 WebP;
- crop covers to 1200×675 WebP;
- constrain inline images to a maximum width of 1600 px while preserving aspect ratio;
- strip metadata;
- calculate SHA-256;
- write processed files only to the declared `content/seed/content-v2/assets/**` path;
- support `--check` and `--write-hashes`;
- produce `.codex-tmp/seed-content-v2/media-report.json`.

The command entry point is:

```powershell
cd apps/web
node scripts/seed/prepare-content-media.mjs --pack ../../../content/seed/content-v2 --check
```

- [ ] **Step 6: Run the Node tests**

Run:

```powershell
cd apps/web
node --test scripts/seed/prepare-content-media.test.mjs
```

Expected: PASS.

- [ ] **Step 7: Commit media tooling**

```powershell
git add .gitignore apps/web/package.json apps/web/package-lock.json apps/web/scripts/seed
git commit -m "feat: 增加种子媒体标准化工具" -m "统一头像、封面和正文图的尺寸、WebP 输出、哈希与项目内临时目录。"
```

## Task 5: Upload normalized media through StorageService

**Files:**
- Create: `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackMediaPublisher.java`
- Create: `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackMediaPublisherTest.java`
- Modify: `apps/server/src/main/java/com/chtholly/storage/StorageService.java`
- Modify: `apps/server/src/main/java/com/chtholly/storage/LocalFileStorageService.java`
- Modify: `apps/server/src/main/java/com/chtholly/storage/OssStorageService.java`
- Modify: `apps/server/src/test/java/com/chtholly/storage/LocalFileStorageServiceTest.java`

- [ ] **Step 1: Write failing media-publisher tests**

```java
@Test
void given_validAsset_when_publish_then_uploadsDeclaredObjectKey() throws Exception {
    when(storage.resolvePublicUrl("seed/content-v2/avatars/night-coder-a1b2c3d4.webp"))
            .thenReturn("/uploads/seed/content-v2/avatars/night-coder-a1b2c3d4.webp");

    PublishedAsset result = publisher.publish(root, asset);

    verify(storage).uploadObject(eq(asset.objectKey()), any(InputStream.class),
            eq("image/webp"), eq(Files.size(root.resolve(asset.file()))));
    assertThat(result.publicUrl()).startsWith("/uploads/");
}

@Test
void given_hashMismatch_when_publish_then_doesNotUpload() {
    assertThatThrownBy(() -> publisher.publish(root, assetWithWrongHash()))
            .hasMessageContaining("sha256 mismatch");
    verifyNoInteractions(storage);
}
```

- [ ] **Step 2: Run and verify failure**

Run:

```powershell
cd apps/server
mvn -q -Dtest=ContentPackMediaPublisherTest test
```

Expected: compilation failure.

- [ ] **Step 3: Implement publisher**

`publishAll` reads only preprocessed files inside the pack root, verifies size and SHA-256, calls `StorageService.uploadObject`, and resolves URLs with `StorageService.resolvePublicUrl`. Object keys must begin with `seed/content-v2/`.

Use content-addressed keys such as `seed/content-v2/assets/<assetKey>-<hashPrefix>.webp` and `seed/content-v2/posts/<postSeedKey>-<hashPrefix>.md`. Publish both normalized images and final Markdown before the MySQL transaction, so a rollback cannot expose new bytes through an old URL.

Add this narrow method to the storage abstraction:

```java
boolean objectExists(String objectKey);
```

`LocalFileStorageService` uses `Files.exists(resolveObjectPath(objectKey))`; `OssStorageService` uses `client.doesObjectExist(bucket, objectKey)`. Track whether each object existed before upload. On failure, delete only content-addressed keys that were absent before this run; never delete or overwrite an accepted old object.

Add a test where `objectExists` returns true and a later upload fails; verify rollback never deletes the pre-existing key.

- [ ] **Step 4: Run tests**

Run:

```powershell
cd apps/server
mvn -q -Dtest=ContentPackMediaPublisherTest test
```

Expected: PASS.

- [ ] **Step 5: Commit media publishing**

```powershell
git add apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackMediaPublisher.java apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackMediaPublisherTest.java apps/server/src/main/java/com/chtholly/storage apps/server/src/test/java/com/chtholly/storage/LocalFileStorageServiceTest.java
git commit -m "feat: 通过统一存储发布内容包媒体" -m "上传前校验哈希与路径，失败时仅清理本次运行产生的孤儿对象。"
```

## Task 6: Implement transactional account, post and interaction writes

**Files:**
- Modify: `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackMapper.java`
- Modify: `apps/server/src/main/resources/mapper/ContentPackMapper.xml`
- Create: `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackDatabaseWriter.java`
- Create: `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackReactionApplier.java`
- Create: `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackDatabaseWriterTest.java`
- Create: `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackReactionApplierTest.java`

- [ ] **Step 1: Write failing writer tests**

Cover these cases:

```java
@Test
void given_existingAccountAndPost_when_write_then_updatesSameIdsAndSyncsTags() {
    WriteResult result = writer.write(pack, publishedContent);

    verify(mapper).updateSeedUser(argThat(row -> row.id() == 42L
            && row.handle().equals("shirokuma_on")));
    verify(mapper).updateSeedPost(argThat(row -> row.id() == 99L
            && row.creatorId() == 42L));
    verify(tagService).syncPublishedPostTags(eq(42L), anyList(), eq(post.tags()));
    assertThat(result.postIds()).containsExactly(99L);
}

@Test
void given_realUserComment_when_write_then_neverDeletesOrUpdatesIt() {
    writer.write(pack, publishedContent);
    verify(mapper, never()).updateComment(argThat(row -> !row.seedManaged()));
}
```

Also test new post insertion, old Seed comment update by ID, new comment insertion, nested parent resolution, follow upsert, and duplicate run behavior.

Legacy comments do not currently have stable keys. During the first migration, resolve them by `postId + authorId + legacyOrdinal`, where ordinal is the zero-based order by `created_at, id`; persist the selected ID in `seed_content_identity` immediately. Later runs use only the stored comment identity. Existing follow rows resolve by the unique account pair before their identity is persisted.

- [ ] **Step 2: Add focused mapper statements**

Add exact statements for:

- `updateSeedUserById`, including public handle;
- `findPostStateById`, returning old tags and old content object key;
- `updateSeedPostById`, including public slug;
- `insertSeedPost`;
- `upsertSeedComment`;
- `upsertFollowing` and `upsertFollower`;
- `deactivateSeedFollowingExcept` and `deactivateSeedFollowerExcept`, scoped to pairs where both IDs belong to the eight content-pack accounts;
- snapshot queries for Seed users, Seed posts and Seed-managed interactions.

Every mutation targets an identity-resolved ID. No statement may delete rows by email suffix or slug.

- [ ] **Step 3: Implement transactional writer**

Annotate the public write method `@Transactional`. For each article:

1. resolve author/post IDs;
2. compute Markdown byte size and SHA-256;
3. obtain the already-published, content-addressed Markdown URL and media URLs from `PublishedContent`;
4. update the old row or insert a new row;
5. call `TagService.syncPublishedPostTags` with old and new tags;
6. store `img_urls` with the cover URL first, followed by inline URLs in Markdown order;
7. upsert only manifest-declared Seed comments and follows;
8. set `rel_status=0` for obsolete old Seed-to-Seed follow pairs not declared by the pack, without touching any pair involving a real registered user;
9. update identity hashes.

Do not call Elasticsearch or cache invalidation inside this database transaction.

- [ ] **Step 4: Apply reactions through CounterService after database commit**

Create `ContentPackReactionApplier`. The import orchestrator calls it only after `ContentPackDatabaseWriter.write` has returned and the transaction has committed. For each manifest reaction, call only the idempotent public methods:

```java
switch (reaction.type()) {
    case "like" -> counterService.like("post", String.valueOf(postId), accountId);
    case "fav" -> counterService.fav("post", String.valueOf(postId), accountId);
    default -> throw new IllegalArgumentException("Unsupported reaction: " + reaction.type());
}
```

Do not write Redis bitmap or SDS keys directly.

Reconcile only the eight Seed accounts: for every Seed-account/post pair not declared in `interactions.yml`, call `unlike` or `unfav` only when `isLiked` or `isFaved` is true. This removes obsolete generated reactions while preserving every reaction made by a real registered user.

For views, inject `CounterEventPublisher` and treat the manifest value as a minimum baseline, not an absolute replacement. Read the current `view` count through `CounterService.getCounts`; publish one `CounterEvent` with `metric=view`, the schema view index, and `delta = minimumCount - current` only when current is lower. Never decrement views, because current counts may include real visitors. Poll the public count read with a short bounded condition loop until the aggregation is visible or report `partial`.

For newly inserted posts, use `UserCounterService.incrementPosts(authorId, createdCount)` after commit. Updated existing posts do not change the user post counter.

- [ ] **Step 5: Run writer tests**

Run:

```powershell
cd apps/server
mvn -q -Dtest=ContentPackDatabaseWriterTest test
```

Expected: PASS.

- [ ] **Step 6: Commit database writer**

```powershell
git add apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackMapper.java apps/server/src/main/resources/mapper/ContentPackMapper.xml apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackDatabaseWriter.java apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackReactionApplier.java apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackDatabaseWriterTest.java apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackReactionApplierTest.java
git commit -m "feat: 原位更新种子账号文章与互动" -m "事务内复用稳定 ID，重写公开资料和正文，并通过正式计数服务应用点赞收藏。"
```

## Task 7: Orchestrate dry-run, snapshots, cache invalidation and indexing

**Files:**
- Create: `apps/server/src/main/java/com/chtholly/seed/contentpack/model/ContentPackImportReport.java`
- Create: `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackSnapshotWriter.java`
- Create: `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackImportService.java`
- Create: `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackSnapshotWriterTest.java`
- Create: `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackImportServiceTest.java`
- Create: `apps/server/src/test/java/com/chtholly/search/index/SearchIndexServiceTest.java`

- [ ] **Step 1: Write failing orchestration tests**

```java
@Test
void given_dryRun_when_import_then_validatesWithoutMediaOrDatabaseWrites() {
    ContentPackImportReport report = service.run(root, true);

    assertThat(report.status()).isEqualTo("validated");
    verifyNoInteractions(mediaPublisher, databaseWriter, searchIndexService);
}

@Test
void given_indexFailure_when_databaseCommitted_then_reportsPartialAndKeepsMysqlResult() {
    when(databaseWriter.write(any(), any(PublishedContent.class))).thenReturn(writeResult(40));
    doThrow(new IllegalStateException("ES unavailable"))
            .when(searchIndexService).upsertPost(anyLong());

    ContentPackImportReport report = service.run(root, false);

    assertThat(report.status()).isEqualTo("partial");
    assertThat(report.failedStage()).isEqualTo("search-index");
    verify(databaseWriter).write(any(), any(PublishedContent.class));
}
```

Add tests proving validation failure stops before media, media failure stops before database, every changed post invalidates `PostCacheInvalidator`, and unchanged hashes skip writes/indexing.
Also prove a database failure calls `mediaPublisher.rollbackNewObjects(published)`, while a post-commit reaction or user-counter failure returns `partial` with failed stage `runtime-state` and remains safe to retry.

- [ ] **Step 2: Implement stage ordering**

Use this exact sequence:

```java
ContentPack pack = loader.load(root);
ValidationResult validation = validator.validate(pack);
QualityGateResult quality = qualityGate.audit(pack);
if (dryRun) return ContentPackImportReport.validated(pack, validation, quality);

SnapshotRef snapshot = snapshotWriter.write(pack);
PublishedContent published = mediaPublisher.publishAll(pack);
WriteResult write;
try {
    write = databaseWriter.write(pack, published);
} catch (RuntimeException e) {
    mediaPublisher.rollbackNewObjects(published);
    throw e;
}
reactionApplier.apply(pack.reactions(), pack.views(), write.identities());
write.createdPostCountsByAuthor().forEach(userCounterService::incrementPosts);
write.changedPostIds().forEach(cacheInvalidator::invalidate);
IndexResult index = indexChangedPosts(write.changedPostIds());
return ContentPackImportReport.completed(pack, snapshot, write, index);
```

Before a formal write, reject any manifest whose stage is not `complete`. A dry-run accepts `review` packs so the representative batch can use the same loader and quality gates without being importable.

`ContentPackSnapshotWriter` uses the focused snapshot queries from `ContentPackMapper` and writes UTF-8 JSON only beneath `.codex-tmp/seed-content-v2/<runId>/`. Reports contain no credentials. Test that account IDs, post IDs, public fields, content URLs and Seed-managed interaction IDs are present, while passwords, phone numbers and tokens are absent.

- [ ] **Step 3: Make indexing failures observable**

The existing `SearchIndexService.upsertPost` logs and swallows failures, so add a narrow result-returning method without changing existing callers:

```java
public boolean tryUpsertPost(long id) {
    try {
        upsertPostOrThrow(id);
        return true;
    } catch (Exception e) {
        log.error("Index upsert failed for post {}: {}", id, e.getMessage(), e);
        return false;
    }
}
```

Refactor `upsertPost` to delegate to the same private throwing implementation. The importer uses `tryUpsertPost` and reports `partial` if any ID returns false.

Local storage returns site-relative URLs such as `/uploads/...`. Add `search.content-base-url` and resolve only those relative URLs before `fetchContentSafe`; keep absolute OSS URLs unchanged:

```java
private String absoluteContentUrl(String url) {
    if (url == null || url.isBlank()) return null;
    if (url.startsWith("http://") || url.startsWith("https://")) return url;
    if (url.startsWith("/")) return contentBaseUrl.replaceAll("/$", "") + url;
    throw new IllegalArgumentException("Unsupported content URL: " + url);
}
```

`SearchIndexServiceTest` must prove `/uploads/post.md` becomes `http://localhost:8888/uploads/post.md`, so local-mode content is indexed from the full Markdown rather than falling back to the 50-character description.

- [ ] **Step 4: Run orchestration tests**

Create `SearchIndexServiceTest` with mocked Elasticsearch dependencies and assert that `tryUpsertPost` returns false when the throwing core fails and true when it succeeds. Run:

```powershell
cd apps/server
mvn -q '-Dtest=ContentPackImportServiceTest,ContentPackSnapshotWriterTest,SearchIndexServiceTest' test
```

Expected: PASS.

- [ ] **Step 5: Commit orchestration**

```powershell
git add apps/server/src/main/java/com/chtholly/seed/contentpack/model/ContentPackImportReport.java apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackSnapshotWriter.java apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackImportService.java apps/server/src/main/java/com/chtholly/search/index/SearchIndexService.java apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackImportServiceTest.java apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackSnapshotWriterTest.java apps/server/src/test/java/com/chtholly/search/index/SearchIndexServiceTest.java
git commit -m "feat: 编排内容包安全导入与恢复报告" -m "增加 dry-run、快照、缓存失效和可观测索引结果，数据库成功而 ES 失败时返回 partial。"
```

## Task 8: Expose the content-pack CLI mode

**Files:**
- Modify: `apps/server/src/main/java/com/chtholly/seed/SeedProperties.java`
- Modify: `apps/server/src/main/java/com/chtholly/seed/SeedRunMode.java`
- Modify: `apps/server/src/main/java/com/chtholly/seed/SeedRunner.java`
- Modify: `apps/server/src/main/resources/application.yml`
- Modify: `scripts/dev/run-seed.ps1`
- Create: `apps/server/src/test/java/com/chtholly/seed/SeedRunnerTest.java`
- Modify: `apps/server/src/test/java/com/chtholly/seed/SeedOrchestratorTest.java`

- [ ] **Step 1: Write failing CLI routing tests**

Add tests proving `content-pack` and `content_pack` both parse to `CONTENT_PACK`, and `SeedRunner` delegates that mode to `ContentPackImportService` instead of `SeedOrchestrator`.

- [ ] **Step 2: Add properties**

```java
private String contentPackPath = "../../content/seed/content-v2";
```

```yaml
seed:
  content-pack-path: ${SEED_CONTENT_PACK_PATH:../../content/seed/content-v2}
search:
  content-base-url: ${SEARCH_CONTENT_BASE_URL:http://localhost:${SERVER_PORT:8888}}
```

Do not put credentials or absolute developer paths in configuration.

- [ ] **Step 3: Route CONTENT_PACK separately**

```java
if (mode == SeedRunMode.CONTENT_PACK) {
    ContentPackImportReport report = contentPackImportService.run(
            Path.of(properties.getContentPackPath()), dryRun);
    log.info("Seed content pack finished: {}", report);
    return;
}
```

Legacy `FULL`, `BANGUMI`, `ACCOUNTS`, and `CONTENT_ONLY` behavior remains unchanged.

- [ ] **Step 4: Update PowerShell runner**

Add `content_pack` and `content-pack` to `ValidateSet`. Do not reset or reuse the old marker for this mode. Pass `--seed.dry-run=true` when `-DryRun` is supplied.

Commands become:

```powershell
.\scripts\dev\run-seed.ps1 -Mode content_pack -DryRun
.\scripts\dev\run-seed.ps1 -Mode content_pack
```

- [ ] **Step 5: Run CLI-related tests**

Run:

```powershell
cd apps/server
mvn -q '-Dtest=SeedRunModeTest,SeedRunnerTest,SeedOrchestratorTest' test
```

Expected: PASS; legacy tests remain green.

- [ ] **Step 6: Commit CLI integration**

```powershell
git add apps/server/src/main/java/com/chtholly/seed/SeedProperties.java apps/server/src/main/java/com/chtholly/seed/SeedRunMode.java apps/server/src/main/java/com/chtholly/seed/SeedRunner.java apps/server/src/main/resources/application.yml scripts/dev/run-seed.ps1 apps/server/src/test/java/com/chtholly/seed
git commit -m "feat: 增加版本化内容包导入模式" -m "通过 content_pack 模式提供 dry-run 和正式运行，同时保留旧 Seed 模式兼容性。"
```

## Task 9: Create the eight account profiles and avatar set

**Files:**
- Create: `content/seed/content-v2/manifest.yml`
- Create: `content/seed/content-v2/accounts.yml`
- Create: `content/seed/content-v2/assets.yml`
- Create: `content/seed/content-v2/assets/avatars/*.webp`

- [ ] **Step 1: Write the exact account manifest**

Initialize `manifest.yml` with `version: content-v2`, `namespace: launch-community`, `stage: review`, `expectedAccounts: 8`, `expectedPosts: 0`, and an empty category map. Task 10 raises the review count to 10; Task 11 is the only task that marks the pack complete.

Create these public identities while preserving the listed legacy seedKeys:

| seedKey | nickname | handle | primary voice |
|---|---|---|---|
| night-coder | 白熊没关机 | shirokuma_on | Java/Python 后端排障，短句和日志 |
| algo-runner | 存档点404 | savepoint_404 | RAG/Embedding/Agent 小实验 |
| anime-critic | 云隙_Kumo | kumo_gap | 新番与情绪细节 |
| design-sis | 电波塔17号 | denpa_17 | 分镜、演出和声音长评 |
| moyu-master | Rin今天也AFK | rin_afk | 游戏社区式动画和短日常 |
| indie-dev | 猫又三丁目 | nekomata_3 | OST、角色与声优表现 |
| book-notes | 盐渍青梅 | ume_shio | 阅读与页边笔记 |
| photo-walker | 雾岛便利店 | kirishima24h | 摄影、街道和夜行 |

Every account gets a 100–180 Chinese-character bio, 4–6 interests, 3–5 voice rules, 3 banned phrases, and a knowledge boundary.

- [ ] **Step 2: Source or create eight visibly different avatars**

Use this exact mix:

- five original anime-style avatars with different composition, palette and rendering style;
- one hand-drawn animal/mascot;
- one pixel/game icon;
- one landscape/photo crop.

Do not reuse the rejected `output/Chtholly*.png` set. Do not use a real stranger's face or a recognizable copyrighted anime character.

When online material is needed, use the available `web-access` skill for discovery and retrieval, save the selected source beneath the project-local ignored `.codex-tmp/seed-content-v2/sources` directory, and record its original URL in `assets.yml`.

- [ ] **Step 3: Normalize and validate avatars**

Run:

```powershell
cd apps/web
node scripts/seed/prepare-content-media.mjs --pack ../../../content/seed/content-v2 --write-hashes
node scripts/seed/prepare-content-media.mjs --pack ../../../content/seed/content-v2 --check
```

Expected: eight 512×512 WebP files, eight distinct SHA-256 values, no validation errors.

- [ ] **Step 4: Run backend dry validation against profile-only fixture mode**

Add `--allow-incomplete-posts` only to the authoring media tool, not to the formal backend importer. The formal content pack remains invalid until Task 10 adds representative posts.

- [ ] **Step 5: Commit accounts and avatars**

```powershell
git add content/seed/content-v2/manifest.yml content/seed/content-v2/accounts.yml content/seed/content-v2/assets.yml content/seed/content-v2/assets/avatars
git commit -m "feat: 重塑初始社区账号与头像" -m "以贴吧和游戏社区风格更新八个公开身份，并配置二次元为主的差异化头像。"
```

## Task 10: Author the representative 10-post review batch

**Files:**
- Create: `content/seed/content-v2/posts.yml`
- Create: `content/seed/content-v2/posts/*.md`
- Create: `content/seed/content-v2/assets/covers/*.webp`
- Create: `content/seed/content-v2/assets/inline/*.webp`
- Modify: `content/seed/content-v2/assets.yml`
- Create: `content/seed/content-v2/editorial-feedback.yml` after user review

- [ ] **Step 1: Create exact Briefs for the review batch**

Author these ten posts first:

1. 白熊没关机：《一次线程池误判：真正堵住我的其实是连接池》
2. 白熊没关机：《Python 清洗一份番剧导出表，我最后只保留了 37 行》
3. 存档点404：《我给 Agent 加了三个工具，它反而更容易走错路》
4. 存档点404：《召回 3 条还是 8 条？我拿 20 个问题跑了一遍》
5. 云隙_Kumo：《第七话那顿没吃完的晚饭，比告白更让我在意》
6. 电波塔17号：《我不太喜欢这次大结局，但不是因为它不够圆满》
7. Rin今天也AFK：《为了联动皮肤补完动画，结果先被片尾曲留下了》
8. 猫又三丁目：《有些片尾曲要等最后一个镜头结束才舍得切走》
9. 盐渍青梅：《我把这本书停在第 143 页，两个星期没往后翻》
10. 雾岛便利店：《雨停后的自动贩卖机，比平时亮了一点》

For each Brief, fill all six required fields: facts/anchors, voice, position, format, media plan and sources. Anime claims must be checked against Bangumi or official material; technical claims must use official Java, Python, Redis, MySQL, Elasticsearch, Spring or model-provider documentation.

All online fact and image research during execution must use the `web-access` skill. Persist source URLs and a short factual note in the Brief; do not paste long copyrighted source text into Markdown.

- [ ] **Step 2: Write and edit the Markdown**

Enforce the chosen formats:

- two technical incident reports;
- two AI experiments with result tables;
- two anime close readings;
- two short community-style media posts;
- one reading note;
- one photo essay.

Do not apply identical H2/H3 skeletons. Keep each within its declared 600–2,600-character target.

- [ ] **Step 3: Prepare covers and inline media**

Every post receives one 1200×675 cover. At least seven of the ten receive inline images; technical inline media must be diagrams, terminal/result captures or charts rather than generic laptop photography.

- [ ] **Step 4: Run authoring gates**

Run:

```powershell
cd apps/web
node scripts/seed/prepare-content-media.mjs --pack ../../../content/seed/content-v2 --write-hashes
node scripts/seed/prepare-content-media.mjs --pack ../../../content/seed/content-v2 --check
cd ../server
mvn -q '-Dtest=ContentPackValidatorTest,ContentPackQualityGateTest' test
```

Also run the content-pack CLI dry-run once Task 8 is available:

```powershell
cd ../..
.\scripts\dev\run-seed.ps1 -Mode content_pack -DryRun
```

Set `manifest.stage: review`, `expectedPosts: 10`, and review-batch category counts to backend/AI 4, anime 4, and life/photo/reading 2. Expected: status `validated`, no asset, fact-anchor, duplicate-lead or banned-phrase error. A formal non-dry import must reject the review-stage pack.

- [ ] **Step 5: Render a review index**

Create `.codex-tmp/seed-content-v2/review/index.html` linking each Markdown-rendered article, cover, inline images and author profile. Serve it from a project-local ignored directory; do not place it on C:.

- [ ] **Step 6: Stop for user review**

Ask the user to review all ten representative posts. Record feedback in `content/seed/content-v2/editorial-feedback.yml` with explicit global rules and per-post corrections. Do not author the remaining 30 posts until this review is approved.

- [ ] **Step 7: Commit the approved representative batch**

```powershell
git add content/seed/content-v2
git commit -m "content: 完成首批社区代表文章" -m "交付十篇覆盖动漫、后端、Agent、阅读和摄影的图文代表作，并通过用户抽样复核。"
```

## Task 11: Complete all 40 posts and media

**Files:**
- Modify: `content/seed/content-v2/manifest.yml`
- Modify: `content/seed/content-v2/posts.yml`
- Create/Modify: `content/seed/content-v2/posts/*.md`
- Create: `content/seed/content-v2/assets/covers/*.webp`
- Create: `content/seed/content-v2/assets/inline/*.webp`
- Modify: `content/seed/content-v2/assets.yml`

- [ ] **Step 1: Add the remaining exact editorial slate**

Change `manifest.stage` to `complete`, `expectedPosts` to `40`, and expected categories to anime 19, backend/AI 11, and life/photo/reading 10 only after all remaining files exist.

Complete these remaining titles.

**白熊没关机（remaining 4）**
- 《Redis 报 WRONGTYPE 的那晚，我先怀疑错了服务》
- 《MyBatis 批量查询少了一次循环，接口却快了不止一点》
- 《我把凌晨日志按请求链重排以后，那个超时终于有了形状》
- 《Python 小脚本跑了半年，第一次认真给它补退出码》

**存档点404（remaining 3）**
- 《Embedding 很像，不代表两篇文章真的在说同一件事》
- 《给 RAG 做一次失败样本复盘，比再调一轮 prompt 有用》
- 《我开始记录 Agent 的第一步，因为很多错误从那里就已经发生了》

**云隙_Kumo（remaining 5）**
- 《芙莉莲走得很慢，所以告别才没有被剧情带过去》
- 《重看〈夏目友人帐〉以后，我开始在意那些没有被收服的妖怪》
- 《〈少女终末旅行〉最安静的地方，不是没有人说话》
- 《玉子市场里那条每天都会经过的路，为什么让人安心》
- 《我喜欢〈比宇宙更远的地方〉，但不是因为“青春就该出发”》

**电波塔17号（remaining 4）**
- 《〈孤独摇滚！〉第八话的舞台，不只是在画“成长”》
- 《轻音部的空镜为什么总比台词多停半秒》
- 《〈奇巧计程车〉把线索藏进聊天里，也藏进声音里》
- 《我对〈Sonny Boy〉的结尾仍然有意见，但愿意再看一次》

**Rin今天也AFK（remaining 3）**
- 《补番列表攒到 86 部以后，我决定先删掉“必看神作”》
- 《游戏活动长草的第三天，我把一部十二集动画看完了》
- 《周六下午 AFK 两小时，回来发现公会并没有倒闭》

**猫又三丁目（remaining 3）**
- 《我会为了四十秒的角色歌前奏，把整张专辑再听一遍》
- 《配音没有哭出来的那一段，反而最接近角色》
- 《整理一份夜间动画 OST：不要随机播放，也别开太响》

**盐渍青梅（remaining 4）**
- 《读书笔记不必完整，我现在只记让我停下来的地方》
- 《睡前读散文的坏处，是有时会突然完全不困》
- 《旧书店买回来的书里，夹着一张过期车票》
- 《这个月没有读完五本书，但有一本翻了三遍》

**雾岛便利店（remaining 4）**
- 《一张虚焦照片我留了三年，因为那天的风很清楚》
- 《周末只走到河边，也能写一篇很短的旅行日记》
- 《凌晨一点的便利店玻璃，会把街灯叠成两层》
- 《动画里的电车站总让我想拍照，现实里却常常来不及》

- [ ] **Step 2: Apply approved editorial feedback to every remaining Brief**

Translate each global feedback rule into a validator/auditor assertion when it can be automated. Per-post feedback stays in the Brief and Markdown.

- [ ] **Step 3: Complete 40 covers and at least 24 illustrated bodies**

Run media preparation with `--write-hashes`, then `--check`. Confirm:

- 40 unique cover asset keys;
- at least 24 posts with inline assets;
- no two avatar hashes equal;
- no missing source metadata;
- no final Markdown remote hotlinks.

- [ ] **Step 4: Run the full content dry-run**

```powershell
.\scripts\dev\run-seed.ps1 -Mode content_pack -DryRun
```

Expected report:

- accounts: 8;
- posts: 40;
- anime: 19;
- backend/AI: 11;
- life/photo/reading: 10;
- covers: 40;
- postsWithInlineMedia: at least 24;
- validation errors: 0;
- quality errors: 0.

- [ ] **Step 5: Commit the complete article and media pack**

```powershell
git add content/seed/content-v2
git commit -m "content: 完成四十篇初始社区图文" -m "补齐动漫、后端 AI 与生活内容矩阵，为全部文章配置封面并完成正文配图和质量扫描。"
```

## Task 12: Add natural, idempotent Seed interactions

**Files:**
- Create/Modify: `content/seed/content-v2/interactions.yml`
- Modify: `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackValidatorTest.java`

- [ ] **Step 1: Define exact distribution constraints**

The manifest must contain:

- 18–30 Seed-managed comments across 40 posts;
- 25%–40% of posts with zero comments;
- no post with more than 6 comments;
- at least 5 nested replies;
- 12–20 directed follows, with each account following 0–4 accounts;
- 45–80 likes and 15–35 favorites;
- a long-tail view baseline for all 40 posts, from ordinary posts in the tens to at most three posts above 300;
- no account reacting to its own post;
- no identical comment text.

- [ ] **Step 2: Write comments against final article text**

Every comment must reference a concrete claim, image, code result, episode moment or expressed disagreement. At least 20% of comments should disagree, ask for evidence, or add a different experience; generic praise such as “很有共鸣”“写得真好”“期待更多” fails validation.

Map and rewrite all 16 legacy generated comments using `legacyOrdinal`; add new comments only after every old generic comment has a stable identity. None of the current generic Seed comments may remain visible unchanged.

- [ ] **Step 3: Encode interactions with stable seedKeys**

Example:

```yaml
comments:
  - seedKey: comment-savepoint-404-on-agent-tools-01
    legacyOrdinal: null
    postSeedKey: post-agent-tools-worse
    authorSeedKey: night-coder
    parentSeedKey: null
    content: "你写第三个工具把上下文挤长以后，我更想看同一批问题只开两个工具的结果。现在这个对比还差一组。"
    createdAt: 2026-05-18T14:23:00Z
views:
  - seedKey: views-agent-tools-worse
    postSeedKey: post-agent-tools-worse
    minimumCount: 186
```

Use separate stable keys for comments, follows and reactions so reruns are idempotent.

- [ ] **Step 4: Run interaction validation**

```powershell
cd apps/server
mvn -q -Dtest=ContentPackValidatorTest test
cd ../..
.\scripts\dev\run-seed.ps1 -Mode content_pack -DryRun
```

Expected: all distribution and reference checks PASS.

- [ ] **Step 5: Commit interactions**

```powershell
git add content/seed/content-v2/interactions.yml apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackValidatorTest.java
git commit -m "content: 重建初始社区互动关系" -m "使用稳定键和非均匀分布补充评论、回复、关注、点赞与收藏，避免固定轮转模式。"
```

## Task 13: Execute the real import and verify every boundary

**Files:**
- No planned source modifications. Any defect discovered here requires a focused failing test in the owning file from Tasks 1–12 before a correction is made.
- Generated, ignored: `.codex-tmp/seed-content-v2/<runId>/**`

- [ ] **Step 1: Capture the pre-import evidence**

Run read-only queries and save results under the project-local ignored report directory:

```powershell
. .\scripts\dev\load-env.ps1
docker exec -e "MYSQL_PWD=$env:MYSQL_PASSWORD" mysql mysql -uroot --default-character-set=utf8mb4 chtholly -N -B -e "SELECT id,email,handle,nickname FROM users WHERE email LIKE '%@seed.chtholly.invalid' ORDER BY id; SELECT id,slug,title,creator_id FROM posts WHERE slug LIKE 'seed-%' ORDER BY id;"
```

Expected: 8 legacy Seed users and 32 legacy Seed posts. The formal importer creates the durable pre-write JSON snapshot through `ContentPackSnapshotWriter`; do not create a second ad-hoc snapshot file.

- [ ] **Step 2: Run formal dry-run**

```powershell
.\scripts\dev\run-seed.ps1 -Mode content_pack -DryRun
```

Expected: status `validated`, zero blocking errors, no MySQL/Storage/ES mutation.

- [ ] **Step 3: Run the formal import**

```powershell
.\scripts\dev\run-seed.ps1 -Mode content_pack
```

Expected: status `completed`; 8 accounts, 40 posts, media uploaded, changed post IDs indexed.

- [ ] **Step 4: Verify MySQL identity preservation and totals**

Run:

```powershell
. .\scripts\dev\load-env.ps1
docker exec -e "MYSQL_PWD=$env:MYSQL_PASSWORD" mysql mysql -uroot --default-character-set=utf8mb4 chtholly -N -B -e "SELECT COUNT(*) FROM users WHERE email LIKE '%@seed.chtholly.invalid'; SELECT COUNT(*) FROM posts p JOIN users u ON u.id=p.creator_id WHERE u.email LIKE '%@seed.chtholly.invalid' AND p.status='published' AND p.visible='public'; SELECT COUNT(*) FROM posts p JOIN users u ON u.id=p.creator_id WHERE u.email LIKE '%@seed.chtholly.invalid' AND JSON_LENGTH(p.img_urls)>0;"
```

Expected: `8`, `40`, `40`. Compare the importer-generated pre-write snapshot with the new identity report and prove the original 8 user IDs and 32 post IDs still exist.

- [ ] **Step 5: Verify ES and Hub pagination**

Run:

```powershell
Invoke-RestMethod 'http://localhost:8888/api/v1/search/hub-feed?page=1&size=8' | Select-Object latestPostsTotal,latestPostsStatus
Invoke-RestMethod 'http://localhost:8888/api/v1/search/hub-feed?page=5&size=8' | Select-Object latestPostsTotal,latestPostsStatus
```

Expected: total includes all public posts, status `ok`, and page 5 contains the tail of the 40 Seed posts. Query at least one title from each category through `/api/v1/search?q=...`.

- [ ] **Step 6: Verify media and post pages**

Use a verification script to request every avatar URL, cover URL, inline URL and Markdown content URL. Expected: all return 2xx and nonzero content length. Open representative pages in a browser at desktop and mobile widths; confirm covers, inline images, author avatars and pagination render correctly.

- [ ] **Step 7: Prove rerun idempotency**

Run the formal import a second time:

```powershell
.\scripts\dev\run-seed.ps1 -Mode content_pack
```

Expected: report shows all unchanged or skipped; MySQL user/post/comment/follow counts and Redis like/favorite facts do not increase.

- [ ] **Step 8: Run targeted and full verification**

```powershell
cd apps/server
mvn -q '-Dtest=ContentPackLoaderTest,ContentPackValidatorTest,ContentPackQualityGateTest,ContentPackIdentityResolverTest,ContentPackMediaPublisherTest,ContentPackDatabaseWriterTest,ContentPackImportServiceTest,SeedOrchestratorTest' test
mvn test
cd ../web
node --test scripts/seed/prepare-content-media.test.mjs
npm run build
cd ../..
git diff --check
git status --short
```

Expected: every command exits 0. Expected WARN logs from deliberate degradation tests are acceptable when Surefire reports success.

- [ ] **Step 9: Commit any evidence documentation, never generated reports**

Do not commit `.codex-tmp`, environment files, downloaded intermediates or run reports. If README usage changed, commit only the stable command documentation:

```powershell
git add README.md apps/server/README.md apps/server/db/README.md
git commit -m "docs: 补充初始内容包导入与验收流程" -m "记录 dry-run、正式导入、幂等重跑和 MySQL/ES/媒体核对命令。"
```

## Task 14: Final review and handoff

**Files:**
- Review: `docs/superpowers/specs/2026-07-11-seed-content-pack-design.md`
- Review: this plan
- Review: all commits created by Tasks 1–13

- [ ] **Step 1: Compare implementation against every design acceptance line**

Produce a short checklist mapping each design requirement to code, content, test output or import report. Fix only confirmed gaps; do not add unrelated refactors.

- [ ] **Step 2: Inspect the final Git history**

```powershell
git log --oneline --decorate -15
git status --short
```

Expected: independently verifiable Chinese Conventional Commits, clean worktree, no push.

- [ ] **Step 3: Deliver the result**

Report:

- account and article totals;
- media coverage;
- representative content reviewed;
- MySQL/ES consistency;
- preserved IDs;
- idempotent rerun evidence;
- backend/frontend verification;
- commit list;
- explicit confirmation that nothing was pushed.

---

# 2026-07-12 Real-Source Revision Implementation Plan

Tasks 15–22 supersede the editorial portions of Tasks 9–14. Reuse the committed loader, stable identity, media publication, transactional writer, import orchestration and CLI work; do not reimplement those components.

## Revised file map

### Content-pack model and validation

- Create: `apps/server/src/main/java/com/chtholly/seed/contentpack/model/SeedSourceDefinition.java`
- Modify: `apps/server/src/main/java/com/chtholly/seed/contentpack/model/ContentPack.java`
- Modify: `apps/server/src/main/java/com/chtholly/seed/contentpack/model/SeedAssetDefinition.java`
- Modify: `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackLoader.java`
- Modify: `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackValidator.java`
- Modify: `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackDatabaseWriter.java`
- Modify: `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackQualityGate.java`
- Test: `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackLoaderTest.java`
- Test: `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackValidatorTest.java`
- Test: `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackDatabaseWriterTest.java`
- Test: `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackQualityGateTest.java`

### Deterministic source acquisition

- Create: `apps/web/scripts/seed/fetch-source-asset.mjs`
- Create: `apps/web/scripts/seed/fetch-source-asset.test.mjs`
- Modify: `apps/web/package.json`

### Real-source content pack

- Delete: `content/seed/content-v2/**`
- Create: `content/seed/content-v3/manifest.yml`
- Create: `content/seed/content-v3/sources.yml`
- Create: `content/seed/content-v3/accounts.yml`
- Create: `content/seed/content-v3/assets.yml`
- Create: `content/seed/content-v3/posts.yml`
- Create: `content/seed/content-v3/interactions.yml`
- Create: `content/seed/content-v3/posts/*.md`
- Create: `content/seed/content-v3/assets/avatars/*`
- Create: `content/seed/content-v3/assets/covers/*`
- Create: `content/seed/content-v3/assets/inline/*`

### Runtime defaults and review tooling

- Modify: `apps/server/src/main/java/com/chtholly/seed/SeedProperties.java`
- Modify: `apps/server/src/main/resources/application.yml`
- Modify: `apps/server/src/test/java/com/chtholly/seed/SeedCliReadOnlySafetyTest.java`
- Create temporarily, never commit: `.codex-tmp/seed-content-v3/review/index.html`
- Create temporarily, never commit: `.codex-tmp/seed-content-v3/review-sources.json`

## Task 15: Add structured provenance and optional post media

**Files:**
- Create: `apps/server/src/main/java/com/chtholly/seed/contentpack/model/SeedSourceDefinition.java`
- Modify: `apps/server/src/main/java/com/chtholly/seed/contentpack/model/ContentPack.java`
- Modify: `apps/server/src/main/java/com/chtholly/seed/contentpack/model/SeedAssetDefinition.java`
- Modify: `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackLoader.java`
- Modify: `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackValidator.java`
- Modify: `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackDatabaseWriter.java`
- Test: `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackLoaderTest.java`
- Test: `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackValidatorTest.java`
- Test: `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackDatabaseWriterTest.java`

- [ ] **Step 1: Write failing loader tests for `sources.yml`**

Add a fixture source and assert it is loaded by key:

```yaml
- key: bgm-mygo-episode-discussion
  type: bangumi-discussion
  title: MyGO 单集讨论
  pageUrl: https://bgm.tv/subject/428735/ep/12
  author: null
  fetchedAt: 2026-07-12T00:00:00Z
  factAnchors: [第12话, live]
  quote: null
  usageNote: 仅提取争议点，不复制楼层正文
```

```java
assertThat(pack.sources()).containsKey("bgm-mygo-episode-discussion");
assertThat(pack.sources().get("bgm-mygo-episode-discussion").type())
        .isEqualTo("bangumi-discussion");
```

- [ ] **Step 2: Run the loader test and confirm the missing model fails**

Run:

```powershell
cd apps/server
mvn -q '-Dtest=ContentPackLoaderTest' test
```

Expected: compilation or assertion failure because `ContentPack.sources()` does not exist.

- [ ] **Step 3: Add the immutable source model and loader boundary**

Create:

```java
public record SeedSourceDefinition(
        String key,
        String type,
        String title,
        String pageUrl,
        String author,
        Instant fetchedAt,
        List<String> factAnchors,
        String quote,
        String usageNote) {
    public SeedSourceDefinition {
        factAnchors = factAnchors == null ? List.of() : List.copyOf(factAnchors);
    }
}
```

Add `Map<String, SeedSourceDefinition> sources` to `ContentPack`. Keep an auxiliary constructor with the previous signature that supplies `Map.of()` so existing unit fixtures remain readable. Load optional `sources.yml` exactly like the existing account and asset lists, indexing by `SeedSourceDefinition::key`.

- [ ] **Step 4: Write failing v3 provenance validation tests**

Add tests that build a `content-v3` manifest and assert these exact diagnostics:

```java
assertThatThrownBy(() -> validator.validate(packWithUnknownSourceKey()))
        .hasMessageContaining("missing post source: post-1 -> missing-source");
assertThatThrownBy(() -> validator.validate(packWithGeneratedAsset()))
        .hasMessageContaining("AI-generated asset forbidden: avatar-1");
assertThatThrownBy(() -> validator.validate(packWithIncompleteWebAsset()))
        .hasMessageContaining("missing asset source page: avatar-1");
```

For `content-v3`, interpret `post.brief().sources()` as source-card keys. Require at least one source per post and require every web asset to have `sourcePageUrl`, `fetchedAt` and `usageNote`. Reject `source`, `sourceUrl` or `sourcePageUrl` values containing `openai-imagegen`, `generated:` or `gocrazyai` case-insensitively.

- [ ] **Step 5: Extend asset provenance fields and implement validation**

Extend `SeedAssetDefinition` after `sourceUrl` with:

```java
String sourcePageUrl,
Instant fetchedAt,
String usageNote,
```

Update loader fixtures and constructor call sites. Apply strict provenance checks only when `manifest.version().equals("content-v3")`, while the AI-generated-source prohibition applies to every pack version.

- [ ] **Step 6: Write failing tests for posts without covers**

```java
SeedPostDefinition noMedia = postWith(null, List.of());
assertThatCode(() -> validator.validate(packWith(noMedia))).doesNotThrowAnyException();
assertThat(ContentPackDatabaseWriter.contentHash(noMedia, 42L, publishedMarkdownOnly()))
        .isNotBlank();
```

Expected before implementation: validator reports a missing cover and the writer requires a published cover.

- [ ] **Step 7: Make cover assets optional end to end**

In `ContentPackValidator`, validate the cover only when nonblank. In `ContentPackDatabaseWriter.contentHash` and `imageUrls`, add the cover hash/URL only when nonblank; keep inline assets ordered after it. Do not synthesize placeholder images.

- [ ] **Step 8: Run targeted tests**

```powershell
cd apps/server
mvn -q '-Dtest=ContentPackLoaderTest,ContentPackValidatorTest,ContentPackDatabaseWriterTest' test
```

Expected: all tests pass.

- [ ] **Step 9: Commit the model boundary**

```powershell
git add apps/server/src/main/java/com/chtholly/seed/contentpack apps/server/src/test/java/com/chtholly/seed/contentpack
git commit -m "feat: 增加内容来源卡与可选文章媒体"
```

## Task 16: Retire the rejected v2 pack and establish the v3 default

**Files:**
- Delete: `content/seed/content-v2/**`
- Create: `content/seed/content-v3/manifest.yml`
- Create: `content/seed/content-v3/sources.yml`
- Create: `content/seed/content-v3/accounts.yml`
- Create: `content/seed/content-v3/assets.yml`
- Create: `content/seed/content-v3/posts.yml`
- Create: `content/seed/content-v3/interactions.yml`
- Modify: `apps/server/src/main/java/com/chtholly/seed/SeedProperties.java`
- Modify: `apps/server/src/main/resources/application.yml`
- Modify: `apps/server/src/test/java/com/chtholly/seed/SeedCliReadOnlySafetyTest.java`

- [ ] **Step 1: Write the failing default-path assertion**

```java
assertThat(new SeedProperties().getContentPackPath())
        .isEqualTo("../../content/seed/content-v3");
```

Also assert `application.yml` contains `${SEED_CONTENT_PACK_PATH:../../content/seed/content-v3}`.

- [ ] **Step 2: Run the safety test and confirm it still points at v2**

```powershell
cd apps/server
mvn -q '-Dtest=SeedCliReadOnlySafetyTest' test
```

Expected: FAIL with the `content-v2` default.

- [ ] **Step 3: Remove only the rejected pack after validating its path**

```powershell
$worktree = (Resolve-Path .).Path
$target = (Resolve-Path content/seed/content-v2).Path
$allowed = (Resolve-Path content/seed).Path
if (-not $target.StartsWith($allowed + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to remove path outside content/seed: $target"
}
git status --short -- content/seed/content-v2
git rm -r -- content/seed/content-v2
if (Test-Path $target) { Remove-Item -LiteralPath $target -Recurse -Force }
```

Expected: only files under this worktree's `content/seed/content-v2` are removed. Do not use `git clean`, `git reset` or touch another worktree.

- [ ] **Step 4: Create the review-stage v3 skeleton**

```yaml
version: content-v3
namespace: launch-community
stage: review
expectedAccounts: 0
expectedPosts: 0
expectedCategories: {}
```

Create empty YAML lists in `sources.yml`, `accounts.yml`, `assets.yml`, `posts.yml`, and all four lists in `interactions.yml`.

- [ ] **Step 5: Switch both runtime defaults to v3**

Change `SeedProperties.contentPackPath` and `application.yml`; do not change environment-variable override behavior.

- [ ] **Step 6: Prove the empty review pack validates without mutation**

```powershell
.\scripts\dev\run-seed.ps1 -Mode content_pack -DryRun
```

Expected: `status=validated`, namespace `launch-community`, version `content-v3`, no validation or quality errors.

- [ ] **Step 7: Commit the version transition**

```powershell
git add apps/server/src/main/java/com/chtholly/seed/SeedProperties.java apps/server/src/main/resources/application.yml apps/server/src/test/java/com/chtholly/seed/SeedCliReadOnlySafetyTest.java content/seed
git commit -m "refactor: 将初始内容包切换到真实来源版本"
```

## Task 17: Add a deterministic network-asset fetcher

**Files:**
- Create: `apps/web/scripts/seed/fetch-source-asset.mjs`
- Create: `apps/web/scripts/seed/fetch-source-asset.test.mjs`
- Modify: `apps/web/package.json`

- [ ] **Step 1: Write failing tests around a local HTTP server**

The test must cover one redirect, a required custom `User-Agent`, byte preservation and metadata output:

```js
assert.equal(result.sha256, createHash("sha256").update(body).digest("hex"));
assert.equal(result.sourceUrl, `${origin}/redirect`);
assert.equal(result.finalUrl, `${origin}/avatar.jpg`);
assert.equal(result.fetchedAt, "2026-07-12T00:00:00.000Z");
assert.deepEqual(readFileSync(output), body);
```

Use `--fetched-at` in tests; production calls may default to the current instant. No test may call the public internet.

- [ ] **Step 2: Run the Node test and confirm the script is absent**

```powershell
cd apps/web
node --test scripts/seed/fetch-source-asset.test.mjs
```

Expected: FAIL because the module cannot be imported.

- [ ] **Step 3: Implement the narrow CLI**

Supported arguments:

```text
--url <public image URL>
--source-page <page where the image was discovered>
--output <project-local destination>
--user-agent <descriptive user agent>
--fetched-at <ISO instant, optional>
```

Reject destinations outside the current repository, non-HTTP(S) URLs, responses larger than 15 MiB, non-2xx final responses and content types outside `image/jpeg`, `image/png`, `image/webp`, `image/gif`. Write the image once, then print one JSON object containing `sourceUrl`, `sourcePageUrl`, `finalUrl`, `fetchedAt`, `sha256`, `contentType` and `bytes`. Do not create an internet-dependent build step.

- [ ] **Step 4: Run tests and add a package script**

```json
"seed:fetch-source": "node scripts/seed/fetch-source-asset.mjs"
```

```powershell
node --test scripts/seed/fetch-source-asset.test.mjs
```

Expected: PASS.

- [ ] **Step 5: Commit the fetcher**

```powershell
git add apps/web/package.json apps/web/scripts/seed/fetch-source-asset.mjs apps/web/scripts/seed/fetch-source-asset.test.mjs
git commit -m "feat: 增加公开来源图片固定工具"
```

## Task 18: Build eight real-looking accounts and approved avatars

**Files:**
- Modify: `content/seed/content-v3/manifest.yml`
- Modify: `content/seed/content-v3/accounts.yml`
- Modify: `content/seed/content-v3/assets.yml`
- Create: `content/seed/content-v3/assets/avatars/*`

- [ ] **Step 1: Acquire the eight approved visual categories**

Use the approved Bangumi-user-avatar candidates C01, C03, C04, C05, C09 and C12, plus C06 hamster and C08 night sky. Use the real source page and avatar URL already recorded during research. If a URL is unavailable, replace it only with the same category and show the replacement in the visual review page.

Run the fetcher once per asset, always writing the original into `.codex-tmp/seed-content-v3/source-images/`; then normalize with the existing Sharp media tool to 512×512 WebP under `content/seed/content-v3/assets/avatars/`.

- [ ] **Step 2: Write eight concise accounts**

Keep the existing stable `seedKey` and `legacyHandle` values so user IDs remain stable, but replace nickname, handle, bio, tags and voice. Requirements:

```text
nickname: 2–14 visible characters, mixed naming styles
handle: 3–24 ASCII letters/digits/underscore
bio: null, empty, or at most 45 Chinese characters
school: null for all accounts
voice.commonPhrases: empty list
voice.forbiddenExpressions: include the known template phrases
```

Do not copy any source Bangumi username, nickname or profile text. Do not set a gender based on an avatar.

- [ ] **Step 3: Declare full asset provenance**

Each avatar entry must include:

```yaml
source: public-web
sourceUrl: <direct Bangumi avatar URL>
sourcePageUrl: <public Bangumi user page URL>
fetchedAt: <ISO instant>
usageNote: 仅复用公开头像图片，不关联或冒充原用户身份
```

Use content-addressed object keys and the real normalized-file hashes.

- [ ] **Step 4: Run media and dry-run checks**

```powershell
node apps/web/scripts/seed/prepare-content-media.mjs --pack content/seed/content-v3 --write-hashes
node apps/web/scripts/seed/prepare-content-media.mjs --pack content/seed/content-v3 --check
.\scripts\dev\run-seed.ps1 -Mode content_pack -DryRun
```

Expected: 8 accounts, 8 avatars, 0 posts, no AI-generated source, status `validated`.

- [ ] **Step 5: Create a local visual contact sheet and stop for account review**

Generate `.codex-tmp/seed-content-v3/review/accounts.html` showing each avatar at 64 and 128 pixels beside nickname, handle and bio. Verify every local asset reference returns 200. Do not commit the review page.

- [ ] **Step 6: Commit only after user approval**

```powershell
git add content/seed/content-v3/manifest.yml content/seed/content-v3/accounts.yml content/seed/content-v3/assets.yml content/seed/content-v3/assets/avatars
git commit -m "feat: 重建真实来源社区账号与头像"
```

## Task 19: Author and review three real-source sample posts

**Files:**
- Modify: `content/seed/content-v3/manifest.yml`
- Modify: `content/seed/content-v3/sources.yml`
- Modify: `content/seed/content-v3/assets.yml`
- Modify: `content/seed/content-v3/posts.yml`
- Create: `content/seed/content-v3/posts/*.md`
- Create: `content/seed/content-v3/assets/covers/*`
- Create: `content/seed/content-v3/assets/inline/*`
- Modify: `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackQualityGate.java`
- Test: `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackQualityGateTest.java`

- [ ] **Step 1: Select source bundles before writing prose**

Use `web-access` for all internet work. Create three bundles:

1. one Bangumi discussion/log plus the subject page and official anime page;
2. one real GitHub Issue plus official Java/Python/LLM documentation;
3. one Bangumi discussion theme or game announcement suitable for a 180–450 Chinese-character community note.

Record source cards first. A source bundle is rejected if it cannot support a concrete title without invented personal history.

- [ ] **Step 2: Write failing quality tests for v3 formats and template-family repetition**

Add format-specific bounds:

```java
ContentPack pack = pack(root,
        post("community-short", "锚点", "锚点" + "短".repeat(177), "community-note"),
        post("community-min", "锚点", "锚点" + "短".repeat(178), "community-note"),
        post("issue-short", "锚点", "锚点" + "短".repeat(297), "issue-note"));
ContentPackQualityGate.QualityGateResult result = gate.audit(pack);
assertTrue(result.errors().stream().anyMatch(value -> value.equals(
        "body length out of range: community-short -> 179")));
assertFalse(result.errors().stream().anyMatch(value -> value.contains("community-min")));
assertTrue(result.errors().stream().anyMatch(value -> value.equals(
        "body length out of range: issue-short -> 299")));
```

Use these bounds: `community-note` 180–700 Han characters, `issue-note` 300–1,800, `review` 450–2,200. Add corpus-level errors when more than one post uses the same template family: `不是…而是…`, `真正…在意`, `总的来说`, or identical closing conclusions.

- [ ] **Step 3: Implement the minimal quality-gate changes**

Introduce a `LengthBounds` record and a format map. Preserve the existing `short-note` behavior for old test fixtures, but require every v3 sample to use `community-note`, `issue-note` or `review`.

- [ ] **Step 4: Write three structurally different Markdown posts**

- Anime review: 450–900 Han characters, one specific disagreement, no universal conclusion.
- Issue note: 450–1,200 Han characters, include the real error/behavior, affected version, reproduction and source link; do not say it happened in the author's company.
- Community note: 180–450 Han characters, no headings and no final takeaway.

At most one short attributed quotation may appear in each post. Keep it under 25 words from its source. Every claim that looks factual must map to a source-card fact anchor.

- [ ] **Step 5: Use only directly relevant real media**

At least one sample must have no inline image. A cover is optional. Where media is used, acquire a Bangumi subject cover, official visual, Issue screenshot or real command output; do not create AI art or decorative SVG.

- [ ] **Step 6: Run evidence, media and pack validation**

```powershell
cd apps/server
mvn -q '-Dtest=ContentPackQualityGateTest,ContentPackLoaderTest,ContentPackValidatorTest' test
cd ../..
node apps/web/scripts/seed/prepare-content-media.mjs --pack content/seed/content-v3 --write-hashes
node apps/web/scripts/seed/prepare-content-media.mjs --pack content/seed/content-v3 --check
.\scripts\dev\run-seed.ps1 -Mode content_pack -DryRun
git diff --check
```

Expected: 3 posts, all source references resolve, media hashes pass, status `validated`.

- [ ] **Step 7: Build the source-aware review page and stop**

Create `.codex-tmp/seed-content-v3/review/index.html` with rendered Markdown, actual media, author card and a collapsed source panel listing source title, page URL, fact anchors and any short quote. Verify all local references return 200. Do not create interactions and do not commit the sample posts before explicit user approval.

- [ ] **Step 8: Commit approved samples**

```powershell
git add apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackQualityGate.java apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackQualityGateTest.java content/seed/content-v3
git commit -m "feat: 增加真实来源代表文章"
```

## Task 20: Expand the approved editorial system to about forty posts

**Files:**
- Modify: `content/seed/content-v3/manifest.yml`
- Modify: `content/seed/content-v3/sources.yml`
- Modify: `content/seed/content-v3/assets.yml`
- Modify: `content/seed/content-v3/posts.yml`
- Create: `content/seed/content-v3/posts/*.md`
- Create: `content/seed/content-v3/assets/covers/*`
- Create: `content/seed/content-v3/assets/inline/*`

- [ ] **Step 1: Freeze the editorial matrix before drafting**

Create 40 rows in `posts.yml` and set the manifest categories to `番剧: 20`、`技术: 10`、`生活: 10`. Assign all 32 historical Seed slugs exactly once through `legacySlug`; the remaining 8 posts use `legacySlug: null`. Use exactly 14 `community-note`, 8 `issue-note` and 18 `review` posts.

- [ ] **Step 2: Create source cards in batches of five posts**

For each row, record at least one source card before prose. Anime reviews require a Bangumi subject/discussion source plus an official source for production facts. Technical posts require a real Issue/advisory plus official documentation. Miscellaneous posts require a public announcement, book publisher page, public discussion or attributed photography source.

- [ ] **Step 3: Draft five posts, then run the quality gate**

Repeat for each batch:

```powershell
.\scripts\dev\run-seed.ps1 -Mode content_pack -DryRun
```

Expected: `validated` after every batch. Fix source gaps, repeated template families and format bounds before starting the next batch.

- [ ] **Step 4: Acquire media only when it explains the post**

Do not target a coverage percentage. Require all 8 avatars, but allow posts with no media. Every cover/inline image must have a structured source page, direct URL, fetch time, usage note and content hash. Reject AI-generated and decorative filler media.

- [ ] **Step 5: Run the full content-pack checks**

```powershell
node apps/web/scripts/seed/prepare-content-media.mjs --pack content/seed/content-v3 --write-hashes
node apps/web/scripts/seed/prepare-content-media.mjs --pack content/seed/content-v3 --check
cd apps/server
mvn -q '-Dtest=ContentPackLoaderTest,ContentPackValidatorTest,ContentPackQualityGateTest,ContentPackIdentityResolverTest' test
cd ../..
.\scripts\dev\run-seed.ps1 -Mode content_pack -DryRun
```

Expected: 8 accounts, 40 posts with exact 20/10/10 category counts, 32 unique legacy slugs, no provenance or quality errors.

- [ ] **Step 6: Review a stratified ten-post sample**

Generate a local review page containing four anime posts, three technical posts and three miscellaneous posts, including the shortest and longest posts and every media pattern (no media, cover only, cover plus inline). Stop for user approval before interactions or formal import.

- [ ] **Step 7: Commit the approved full corpus**

```powershell
git add content/seed/content-v3
git commit -m "feat: 扩充真实来源初始社区内容"
```

## Task 21: Add restrained interactions and perform the formal import

**Files:**
- Modify: `content/seed/content-v3/interactions.yml`
- Modify: `content/seed/content-v3/manifest.yml`

- [ ] **Step 1: Add non-uniform interactions without generated comments**

Set `stage: complete`. Add follows, likes, favorites and view baselines with stable keys and timestamps after publication. Leave comments empty unless a comment is independently sourced and rewritten under the same provenance rules; do not use LLM-generated filler comments.

- [ ] **Step 2: Dry-run and targeted tests**

```powershell
.\scripts\dev\run-seed.ps1 -Mode content_pack -DryRun
cd apps/server
mvn -q '-Dtest=ContentPackValidatorTest,ContentPackImportServiceTest,ContentPackDatabaseWriterTest,ContentPackReactionApplierTest' test
```

Expected: `validated`; interaction references and timestamps pass.

- [ ] **Step 3: Run the formal import once**

```powershell
cd ../..
.\scripts\dev\run-seed.ps1 -Mode content_pack
```

Expected: `completed`, no pending views and no search-index failures.

- [ ] **Step 4: Verify database, search and media state**

Check 8 Seed accounts, 40 public Seed posts, 32 preserved historical IDs, 8 newly allocated IDs, category totals, tags, counters, ES document count and every stored media/Markdown URL. Query Hub pages until the full tail is visible.

- [ ] **Step 5: Prove idempotency**

Run the same formal import again. Expected: unchanged/skipped posts, stable IDs and no increase in relationship, reaction or view baselines.

- [ ] **Step 6: Commit interactions**

```powershell
git add content/seed/content-v3/interactions.yml content/seed/content-v3/manifest.yml
git commit -m "feat: 完成真实来源内容包互动与导入配置"
```

## Task 22: Full verification, cleanup and handoff

**Files:**
- Review: `docs/superpowers/specs/2026-07-11-seed-content-pack-design.md`
- Review: `docs/superpowers/plans/2026-07-11-seed-content-pack-implementation.md`
- Review: all Task 15–21 commits

- [ ] **Step 1: Run complete backend and frontend verification**

```powershell
cd apps/server
mvn test
cd ../web
node --test scripts/seed/prepare-content-media.test.mjs scripts/seed/fetch-source-asset.test.mjs
npm run build
cd ../..
git diff --check
git status --short
```

Expected: all commands exit 0 and the worktree contains no uncommitted content-pack files.

- [ ] **Step 2: Audit provenance and rejected-content absence**

Search committed `content/seed/content-v3` for `generated:`, `openai-imagegen`, rejected v2 asset keys, copied Bangumi usernames and missing source-card keys. Expected: zero matches except explicit validator test fixtures.

- [ ] **Step 3: Stop temporary services and remove task-only temporary files**

Stop the visual-companion and review servers by their recorded PID. Resolve each deletion target and confirm it is under this worktree's `.codex-tmp` or `.superpowers` directory before removal. Preserve no C-drive or user-home working files.

- [ ] **Step 4: Inspect final history and scope**

```powershell
git log --oneline --decorate -20
git status --short
git diff origin/main...HEAD --stat
```

Expected: technical Chinese Conventional Commits only, no ignored files forced into Git, no push.

- [ ] **Step 5: Deliver the verified result**

Report account/post totals, category and format distribution, media/provenance totals, preserved IDs, import/idempotency evidence, test/build results, commit list and explicit confirmation that nothing was pushed.

## 2026-07-12《末日后酒店》长文范文执行修订

Tasks 19–22 暂停。用户否决三篇短样稿作为博客内容标杆；先完成 Tasks 23–26 的单篇长文审阅门。本节与 Task 19 的三篇短样稿要求冲突时以本节为准。

## Task 23: Add the isolated long-form review quality gate

**Files:**
- Modify: `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackQualityGate.java`
- Modify: `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackQualityGateTest.java`

- [ ] **Step 1: Write exact failing boundaries**

Add four `content-v3` fixtures using format `longform-review`: 3,999 Han characters must fail, 4,000 and 6,000 must pass, and 6,001 must fail. Also assert existing `review` still accepts 450–2,200 only and `community-note` remains 180–700.

- [ ] **Step 2: Run RED**

```powershell
cd apps/server
mvn -q '-Dtest=ContentPackQualityGateTest#enforcesContentV3LongformReviewBounds' test
```

Expected: FAIL because `longform-review` is not yet accepted.

- [ ] **Step 3: Implement the minimal format**

Add `longform-review` to `CONTENT_V3_FORMATS` and map it to `new LengthRange(4_000, 6_000)`. Do not change the bounds for any existing format.

- [ ] **Step 4: Run GREEN and regression**

```powershell
mvn -q '-Dtest=ContentPackQualityGateTest,ContentPackLoaderTest,ContentPackValidatorTest' test
```

Expected: all targeted tests pass.

## Task 24: Replace the rejected sample bundle with one sourced long-form bundle

**Files:**
- Modify: `content/seed/content-v3/manifest.yml`
- Replace: `content/seed/content-v3/sources.yml`
- Replace: `content/seed/content-v3/posts.yml`
- Modify: `content/seed/content-v3/assets.yml`
- Delete: rejected uncommitted sample Markdown files under `content/seed/content-v3/posts/`
- Create temporarily: `.codex-tmp/seed-content-v3/sources/apocalypse-hotel-*`

- [ ] **Step 1: Set the single-post review scope**

Set `stage: review`, `expectedPosts: 1`, and `expectedCategories: { ANIME: 1 }`. Keep the eight approved accounts and their avatar assets unchanged. Keep `interactions.yml` empty.

- [ ] **Step 2: Record the exact source cards**

Create source cards for:

- `https://blog.sakugabooru.com/2026/01/02/apocalypse-hotel-and-legacies/`
- `https://bgm.tv/subject/509986` and the public `rekyrice` collection comment shown on that page
- `https://www.itmedia.co.jp/news/articles/2509/20/news007.html`
- `https://www.animenewsnetwork.com/interview/2025-06-24/opening-the-apocalypse-hotel-with-cygamespictures-president-nobuhiro-takenaka-director-kana-shundo-/.225414`
- `https://nlab.itmedia.co.jp/cont/articles/3456032/`
- `https://www.animatetimes.com/news/details.php?id=1749194269`
- `https://gamebiz.jp/news/411946`
- `https://apocalypse-hotel.jp/`

Fact anchors must cover the project transfer from Liden Films, Takenaka and Murakoshi's reworking, the pandemic delay, Izumi Takemoto's original designs, Kana Shundo's direction, Kouhei Honda's art direction, the episodic structure, and episodes 5/6/11/12. Do not copy Sakuga Blog paragraphs into source-card notes.

- [ ] **Step 3: Fetch the exact eight media sources**

Use `apps/web/scripts/seed/fetch-source-asset.mjs` without overriding `fetchedAt` for:

1. cover: `https://apocalypse-hotel.jp/wp/wp-content/themes/apocalypsehotel/assets/siteinfo/og_image.jpg?v=202504082014`; remove the unrelated TBS `takopi_project` cover declaration and temporary source;
2. `https://blog.sakugabooru.com/wp-content/uploads/2026/01/apohotel1-scaled.jpg`;
3. `https://blog.sakugabooru.com/wp-content/uploads/2026/01/apohotel2.jpg`;
4. `https://blog.sakugabooru.com/wp-content/uploads/2026/01/apohotel3.jpg`;
5. `https://blog.sakugabooru.com/wp-content/uploads/2026/01/apohotel4.jpg`;
6. `https://blog.sakugabooru.com/wp-content/uploads/2026/01/apohotel5-scaled.jpg`;
7. `https://i.imgur.com/37FPqeE.jpg`;
8. `https://i.imgur.com/U0HYg6m.jpg`.

Inspect every source before accepting it. Use the five Sakuga Blog images for production/design/episode transitions and the two Imgur frames for the broken-tree flowers before and after the finale. Record the Sakuga article as the source page for images 2–8.

- [ ] **Step 4: Declare deterministic media assets**

Create one 1200×675 cover asset and seven inline assets named:

- `apocalypse-hotel-production-history`
- `apocalypse-hotel-takemoto-designs`
- `apocalypse-hotel-director-lineage`
- `apocalypse-hotel-episodic-range`
- `apocalypse-hotel-finale-legacy`
- `apocalypse-hotel-tree-before`
- `apocalypse-hotel-tree-after`

Each inline source becomes a WebP of at most 1,600 pixels wide. Preserve source URL, source page, actual fetch time and usage note.

## Task 25: Write the 4,000–6,000 Han-character model article

**Files:**
- Create: `content/seed/content-v3/posts/apocalypse-hotel-legacies.md`
- Replace: `content/seed/content-v3/posts.yml`

- [ ] **Step 1: Create the one post definition**

Use seed key and slug `apocalypse-hotel-legacies`, author `anime-critic`, category `ANIME`, format `longform-review`, the approved cover, and all seven inline assets. Use the title `即使无法生活，也可以停留——《末日后酒店》与那些比世界活得更久的东西`.

- [ ] **Step 2: Write 22–28 natural paragraphs in eight movements**

Write 4,000–6,000 Han characters following the eight-part order in design section 14.3. The production-history sections may translate and localize Sakuga Blog's facts, but must not preserve its paragraph-by-paragraph wording. Attribute no more than one short external quotation. Integrate the `rekyrice` positions explicitly:

- episodic storytelling is both the largest weakness and advantage;
- episode 5's colored time and Yachiyo's lonely smile;
- episode 6's hot spring and rainbow;
- episode 11's post-apocalyptic journey and sense of being alive;
- episode 12's “人类大笨蛋” and the hotel theme;
- `即使无法生活，也可以停留` as the final return point.

Do not add a generic recommendation paragraph or a universal “原创动画应该如何” conclusion.

- [ ] **Step 3: Place the seven inline images at evidence boundaries**

Put each image immediately after the paragraph it supports, with a concise Chinese caption and source label. The two tree images must appear as a before/after pair in the finale section. Do not place two images consecutively without prose between them except for that explicit comparison pair.

- [ ] **Step 4: Run authoring scans**

Count Han characters and paragraphs. Search for repeated template phrases, copied English sentence fragments longer than 25 words, uncited production claims and unmatched media keys. Expected: 4,000–6,000 Han characters, 22–28 prose paragraphs, eight media assets including the cover, and zero unresolved references.

## Task 26: Build the single-article review gate and stop

**Files:**
- Replace temporarily: `.codex-tmp/seed-content-v3/review/index.html`

- [ ] **Step 1: Prepare and verify media**

```powershell
node apps/web/scripts/seed/prepare-content-media.mjs --pack content/seed/content-v3 --write-hashes
node apps/web/scripts/seed/prepare-content-media.mjs --pack content/seed/content-v3 --check
```

- [ ] **Step 2: Run content validation**

```powershell
cd apps/server
mvn -q '-Dtest=ContentPackQualityGateTest,ContentPackLoaderTest,ContentPackValidatorTest' test
cd ../..
.\scripts\dev\run-seed.ps1 -Mode content_pack -DryRun
git diff --check
```

Expected: `status=validated`; validation and quality warnings/errors are empty.

- [ ] **Step 3: Render only the model article**

Build `review/index.html` with the complete article, actual cover and seven inline images, captions, the approved author card, Han-character and paragraph counts, and a collapsed source panel. Verify `index.html`, `accounts.html`, the avatar and all eight article assets return HTTP 200 through port 62456.

- [ ] **Step 4: Stop for user review**

Do not stage or commit the quality-gate code, article, media or content-pack data. Ask the user to approve the single long-form article before resuming Task 20.

## 2026-07-12《末日后酒店》范文二次修订执行计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把已通过结构审阅的《末日后酒店》范文改成 `rekyrice` 本人的安静观看札记，替换氛围封面并让内容包真实绑定站长账号。

**Architecture:** 内容层保留现有文章事实、结构和媒体数量，只重写暴露研究过程或带机械总结感的表达。身份层使用保留作者键 `site-owner`，由 `SiteProperties.ownerUserId()` 在文章写入边界解析，不把管理员混入 Seed 子账号集合、关注清理或互动。媒体层把现有高分辨率天空剧照转为封面，再补一张第 11 集树下剧照维持七张正文图。

**Tech Stack:** Java 21、Spring Boot 3.2.4、JUnit 5、Mockito、YAML、Markdown、Node.js 媒体固定脚本。

---

## Task 27: 支持真实站长作为内容包文章作者

**Files:**
- Modify: `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackValidator.java`
- Modify: `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackDatabaseWriter.java`
- Test: `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackValidatorTest.java`
- Test: `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackDatabaseWriterTest.java`

- [ ] **Step 1: 写出校验器失败测试**

新增测试，把有效 fixture 的文章作者替换为 `site-owner`，断言文章校验通过；再分别构造作者为 `site-owner` 的评论和参与者为 `site-owner` 的关注，断言仍出现缺失账号错误。普通 `absent-author` 必须继续失败。

```java
private static final String SITE_OWNER = "site-owner";

@Test
void acceptsSiteOwnerOnlyAsPostAuthor() throws Exception {
    ContentPack loaded = loader.load(fixtureRoot("valid"));
    SeedPostDefinition original = loaded.posts().getFirst();
    SeedPostDefinition owned = new SeedPostDefinition(
            original.seedKey(), original.legacySlug(), SITE_OWNER, original.title(), original.slug(),
            original.description(), original.category(), original.tags(), original.publishTime(),
            original.markdownFile(), original.coverAsset(), original.inlineAssets(), original.brief(), original.markdown());
    ContentPack pack = new ContentPack(loaded.root(), loaded.manifest(), loaded.accounts(), loaded.assets(),
            List.of(owned), List.of(), List.of(), List.of(), List.of());

    assertThat(validator.validate(pack).warnings()).isEmpty();
}
```

- [ ] **Step 2: 运行校验器 RED**

```powershell
cd apps/server
mvn -q '-Dtest=ContentPackValidatorTest#acceptsSiteOwnerOnlyAsPostAuthor' test
```

Expected: FAIL，错误包含 `missing post author`。

- [ ] **Step 3: 最小化放行保留作者键**

在 `ContentPackValidator` 中增加包内常量和专用判断，只改变文章作者校验：

```java
static final String SITE_OWNER_AUTHOR = "site-owner";

private boolean isKnownPostAuthor(Set<String> accountKeys, String authorSeedKey) {
    return SITE_OWNER_AUTHOR.equals(authorSeedKey) || accountKeys.contains(authorSeedKey);
}
```

评论、关注、反应和其他账号引用继续只接受 `accounts.yml` 中声明的 seed key。

- [ ] **Step 4: 写出数据库作者解析失败测试**

给 writer 测试注入 `new SiteProperties(7L, 888888888888888888L, "", "rekyrice", "rekyrice")`，构造 `post("post-one", "site-owner")`，捕获 `SeedPostRow` 并断言 `creatorId == 7L`；另用 `ownerUserId=0` 断言写入前抛出 `IllegalStateException`，且未调用 `insertSeedPost` 或 `updateSeedPostById`。

- [ ] **Step 5: 运行 writer RED**

```powershell
mvn -q '-Dtest=ContentPackDatabaseWriterTest#givenSiteOwnerAuthor_whenWrite_thenUsesConfiguredOwnerId,ContentPackDatabaseWriterTest#givenInvalidSiteOwnerId_whenWrite_thenFailsBeforePostMutation' test
```

Expected: FAIL，因为 writer 尚未接收 `SiteProperties`，也无法解析 `site-owner`。

- [ ] **Step 6: 在文章写入边界解析站长 ID**

为 `ContentPackDatabaseWriter` 注入 `SiteProperties`，新增方法：

```java
private long resolvePostAuthorId(Map<String, Long> accountIds, String authorSeedKey) {
    if (ContentPackValidator.SITE_OWNER_AUTHOR.equals(authorSeedKey)) {
        long ownerUserId = siteProperties.ownerUserId();
        if (ownerUserId <= 0) {
            throw new IllegalStateException("site.owner-user-id must be positive for site-owner posts");
        }
        return ownerUserId;
    }
    return requireIdentity(accountIds, authorSeedKey, "post author");
}
```

`writePosts` 仅用该方法解析文章作者，不把 `site-owner` 放入 `accountIds`。

- [ ] **Step 7: 运行 GREEN 与回归**

```powershell
mvn -q '-Dtest=ContentPackValidatorTest,ContentPackDatabaseWriterTest,ContentPackImportServiceTest' test
```

Expected: 全部通过；既有 Seed 账号、评论、关注和导入测试行为不变。

## Task 28: 重写范文声音与小标题

**Files:**
- Modify: `content/seed/content-v3/posts/apocalypse-hotel-legacies.md`
- Modify: `content/seed/content-v3/posts.yml`

- [ ] **Step 1: 切换真实作者并保持内容包规模**

在 `posts.yml` 中只把该文 `authorSeedKey` 改为 `site-owner`。`manifest.yml` 保持 `expectedAccounts: 8`、`expectedPosts: 1`，不得新增名为 rekyrice 的 Seed 账号。

- [ ] **Step 2: 替换八个小标题**

正文 H2 必须按以下顺序出现：

```markdown
## 在银河楼亮灯以前
## 那些没有长成标准答案的角色
## 一阵从旧时代吹来的风
## 废墟里仍有晴天
## 客人来过，又把世界带走
## 时间有颜色，温泉通向彩虹
## 一个人走过人类留下的地球
## 没有人回来，酒店仍然开门
```

- [ ] **Step 3: 做全文观看札记式重写**

逐段保留已核实的制作史事实、ep5/6/11/12 分析、24 段左右结构和最终判断，重写暴露研究过程或机械收束的句子。删除正文中的 `Sakuga Blog`、`原文`、`资料将其称为`、`这恰好解释了`、`由此可以看出`、`这意味着` 等元话语；用具体画面、人物动作和站长自己的判断连接段落。不得把外部文章逐段翻译为近似副本。

- [ ] **Step 4: 清理所有可见图片来源**

每张图注只描述画面，不得包含 `来源：`、`Sakuga`、`Animate Times`、`ORICON` 或 URL。内部 `assets.yml` 与 `sources.yml` 的来源字段不删除。

- [ ] **Step 5: 运行内容扫描**

```powershell
$post = 'content/seed/content-v3/posts/apocalypse-hotel-legacies.md'
Select-String -Path $post -Pattern 'Sakuga Blog|来源：|原文|这恰好解释了|由此可以看出|这意味着'
(Select-String -Path $post -Pattern '^## ').Count
```

Expected: 禁用模式零匹配，H2 数量为 8；严格正文汉字仍为 4,000–6,000，重点集数与结论均未丢失。

## Task 29: 更换氛围封面并维持七张正文图

**Files:**
- Modify: `content/seed/content-v3/assets.yml`
- Modify: `content/seed/content-v3/posts.yml`
- Modify: `content/seed/content-v3/sources.yml`
- Replace: `content/seed/content-v3/assets/covers/apocalypse-hotel.webp`
- Create: `content/seed/content-v3/assets/inline/apocalypse-hotel-tree-rest.webp`
- Temporary: `.codex-tmp/seed-content-v3/sources/apocalypse-hotel-tree-rest-actual.jpg`

- [ ] **Step 1: 把天空剧照改作封面源**

使用已固定的高清源 `https://blog.sakugabooru.com/wp-content/uploads/2026/01/apohotel5-scaled.jpg` 重新生成 `apocalypse-hotel-cover`。目标为 1200×675 WebP；八千代在辽阔天空下占较小比例，无标题拼贴。原 `apocalypse-hotel-yachiyo-sky` 不再作为 inline asset。

- [ ] **Step 2: 固定新的第 11 集树下剧照**

使用页面 `https://www.animatetimes.com/news/img.php?id=1749726773&n=4&p=1` 的公开剧照：

```text
https://img2.animatetimes.com/2025/06/6032747a017dc50c2e60544f0b5f8930684abd73081318_22160650_96b471b70187ffac262b8f612250273e6ab4f33d.jpg
```

通过 `fetch-source-asset.mjs` 写入真实抓取时间，再生成 key `apocalypse-hotel-tree-rest`、文件 `inline/apocalypse-hotel-tree-rest.webp`。在 `sources.yml` 新增 `apocalypse-hotel-episode11-stills` 来源卡，在 `assets.yml` 保留来源页、原图 URL、哈希和用途说明。

- [ ] **Step 3: 更新文章媒体引用**

把第 11 集段落中的 `asset:apocalypse-hotel-yachiyo-sky` 替换为 `asset:apocalypse-hotel-tree-rest`，图注只写八千代在树下短暂停留的画面意义。`posts.yml` 的 `inlineAssets` 同步替换，最终仍是 1 张封面加 7 张互不重复的正文图。

- [ ] **Step 4: 写入哈希并检查**

```powershell
node apps/web/scripts/seed/prepare-content-media.mjs --pack content/seed/content-v3 --write-hashes
node apps/web/scripts/seed/prepare-content-media.mjs --pack content/seed/content-v3 --check
```

Expected: 16 个总资产（8 个头像、1 张文章封面、7 张正文图）全部校验通过；新封面 1200×675，正文无重复封面。

## Task 30: 重建预览并完成双重审查

**Files:**
- Replace temporarily: `.codex-tmp/seed-content-v3/review/index.html`

- [ ] **Step 1: 重建真实管理员预览**

预览作者显示 `rekyrice`，不再复用 `anime-critic` 的昵称或头像。页面显示完整正文、1 张新封面、7 张正文图；不生成读者可见的图片来源面板或来源字样。

- [ ] **Step 2: 运行定向测试与只读 dry-run**

```powershell
cd apps/server
mvn -q '-Dtest=ContentPackValidatorTest,ContentPackQualityGateTest,ContentPackLoaderTest,ContentPackDatabaseWriterTest,ContentPackImportServiceTest' test
cd ../..
node apps/web/scripts/seed/prepare-content-media.mjs --pack content/seed/content-v3 --check
.\scripts\dev\run-seed.ps1 -Mode content_pack -DryRun
git diff --check
```

Expected: 测试全部通过；报告为 `validated`，`validationWarnings`、`qualityWarnings`、`qualityErrors` 均为空。

- [ ] **Step 3: 做规格审查**

逐项核对设计第 15 节：8 个精确小标题、`site-owner`、4,000–6,000 汉字、四个重点集数、24 段左右、1+7 图片、可见来源零匹配。发现偏差时返回相应任务修复后重审。

- [ ] **Step 4: 做独立质量审查**

重点检查是否仍有来源腔、翻译腔、AI 式总结、过度抒情和姓名堆砌；检查封面氛围、每张图与相邻段落以及管理员署名。质量审查不修改文件，只给出 `APPROVED` 或准确问题清单。

- [ ] **Step 5: 交付用户复审并停止**

确认预览页与全部图片 HTTP 200、浏览器无破图、暂存区为空。不要提交本轮文章、媒体和导入代码；把审阅链接交给用户，等待最终批准。

## 2026-07-12 首批九篇内容扩写实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在已批准《末日后酒店》范文的基础上，新增五篇动漫、两篇技术、一篇游戏和一篇阅读文章，形成十篇可视化审阅样本。

**Architecture:** 内容包继续使用 `content/seed/content-v3` 的离线 Markdown、YAML 来源卡和固定媒体结构。质量门新增三种篇幅格式；九篇文章按三个内部小批次完成，每篇先核对来源与媒体，再按独立作者声音成文，最后统一生成十篇总览和九篇全文预览。本阶段只运行 dry-run，不写入真实数据库，不生成互动数据。

**Tech Stack:** Java 21、Spring Boot 3.2.4、JUnit 5、YAML、Markdown、Node.js 媒体固定脚本、Python 3（RAG 基线实验）、本地 HTML 审阅页。

---

### Task 31: 为首批扩写增加三种文章格式

**Files:**
- Modify: `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackQualityGate.java`
- Test: `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackQualityGateTest.java`

- [ ] **Step 1: 写出三个格式的精确边界测试**

在 `ContentPackQualityGateTest` 新增：

```java
@Test
void enforcesFirstBatchFormatExactChineseCharacterBoundaries(@TempDir Path root) {
    ContentPack pack = pack("content-v3", root,
            post("feature-1799", "锚", "锚" + "短".repeat(1798), "feature-review"),
            post("feature-1800", "锚", "锚" + "下".repeat(1799), "feature-review"),
            post("feature-3400", "锚", "锚" + "内".repeat(3399), "feature-review"),
            post("feature-3401", "锚", "锚" + "长".repeat(3400), "feature-review"),
            post("technical-1499", "锚", "锚" + "短".repeat(1498), "technical-report"),
            post("technical-1500", "锚", "锚" + "下".repeat(1499), "technical-report"),
            post("technical-2800", "锚", "锚" + "内".repeat(2799), "technical-report"),
            post("technical-2801", "锚", "锚" + "长".repeat(2800), "technical-report"),
            post("essay-799", "锚", "锚" + "短".repeat(798), "personal-essay"),
            post("essay-800", "锚", "锚" + "下".repeat(799), "personal-essay"),
            post("essay-1800", "锚", "锚" + "内".repeat(1799), "personal-essay"),
            post("essay-1801", "锚", "锚" + "长".repeat(1800), "personal-essay"));

    ContentPackQualityGate.QualityGateResult result = gate.audit(pack);

    assertLengthError(result, "feature-1799");
    assertNoLengthError(result, "feature-1800");
    assertNoLengthError(result, "feature-3400");
    assertLengthError(result, "feature-3401");
    assertLengthError(result, "technical-1499");
    assertNoLengthError(result, "technical-1500");
    assertNoLengthError(result, "technical-2800");
    assertLengthError(result, "technical-2801");
    assertLengthError(result, "essay-799");
    assertNoLengthError(result, "essay-800");
    assertNoLengthError(result, "essay-1800");
    assertLengthError(result, "essay-1801");
}

private void assertLengthError(ContentPackQualityGate.QualityGateResult result, String seedKey) {
    assertTrue(result.errors().stream()
            .anyMatch(value -> value.contains("body length") && value.contains(seedKey)));
}

private void assertNoLengthError(ContentPackQualityGate.QualityGateResult result, String seedKey) {
    assertFalse(result.errors().stream()
            .anyMatch(value -> value.contains("body length") && value.contains(seedKey)));
}
```

- [ ] **Step 2: 运行 RED**

```powershell
cd apps/server
mvn -q '-Dtest=ContentPackQualityGateTest#enforcesFirstBatchFormatExactChineseCharacterBoundaries' test
```

Expected: FAIL，三个新格式均出现 `unsupported content-v3 format`。

- [ ] **Step 3: 最小化增加格式与长度边界**

把格式集合与 switch 改为：

```java
private static final Set<String> CONTENT_V3_FORMATS = Set.of(
        "community-note", "issue-note", "review", "longform-review",
        "feature-review", "technical-report", "personal-essay");

LengthRange range = switch (format) {
    case "community-note" -> new LengthRange(180, 700);
    case "issue-note" -> new LengthRange(300, 1_800);
    case "review" -> new LengthRange(450, 2_200);
    case "longform-review" -> new LengthRange(4_000, 6_000);
    case "feature-review" -> new LengthRange(1_800, 3_400);
    case "technical-report" -> new LengthRange(1_500, 2_800);
    case "personal-essay" -> new LengthRange(800, 1_800);
    default -> throw new IllegalStateException("unreachable content-v3 format: " + format);
};
```

- [ ] **Step 4: 运行 GREEN 与既有边界回归**

```powershell
mvn -q '-Dtest=ContentPackQualityGateTest,ContentPackLoaderTest,ContentPackValidatorTest' test
cd ../..
```

Expected: 全部通过；`review` 仍为 450–2,200，`longform-review` 仍为 4,000–6,000。

- [ ] **Step 5: 提交格式质量门**

```powershell
git add -- apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackQualityGate.java apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackQualityGateTest.java
git diff --cached --check
git commit -m "feat: 增加首批扩写文章格式质量门"
```

### Task 32: 固定第一小批次来源与媒体

**Files:**
- Modify: `content/seed/content-v3/sources.yml`
- Modify: `content/seed/content-v3/assets.yml`
- Create: `content/seed/content-v3/assets/covers/shoushimin-cinematography.webp`
- Create: `content/seed/content-v3/assets/covers/hyakuemu-one-hundred-meters.webp`
- Create: `content/seed/content-v3/assets/covers/city-restless-town.webp`
- Create: `content/seed/content-v3/assets/inline/shoushimin-centered-frame.webp`
- Create: `content/seed/content-v3/assets/inline/shoushimin-room-distance.webp`
- Create: `content/seed/content-v3/assets/inline/shoushimin-ordinary-mask.webp`
- Create: `content/seed/content-v3/assets/inline/hyakuemu-body-lines.webp`
- Create: `content/seed/content-v3/assets/inline/hyakuemu-rain-sprint.webp`
- Create: `content/seed/content-v3/assets/inline/hyakuemu-finish.webp`
- Create: `content/seed/content-v3/assets/inline/city-comedy-impact.webp`
- Create: `content/seed/content-v3/assets/inline/city-space-elasticity.webp`
- Create: `content/seed/content-v3/assets/inline/city-line-language.webp`
- Temporary: `.codex-tmp/seed-content-v3/sources/first-batch-*`

- [ ] **Step 1: 新增三个完整来源卡**

来源卡键和入口固定为：

```text
shoushimin-cinematography-sakuga
  https://blog.sakugabooru.com/2025/12/31/shoushimin-series-cinematography/
  https://shoshimin-anime.com/

hyakuemu-production-sakuga
  https://blog.sakugabooru.com/2025/12/27/hyakuemu-100-meters-uoto-kenji-iwaisawa/
  https://hyakuemu-anime.com/

city-animation-production-sakuga
  https://blog.sakugabooru.com/2025/09/29/city-the-animation-final-production-notes-and-kyoto-animations-future/
  https://city-the-animation.com/
```

每张卡记录页面标题、URL、`fetchedAt`、来源类型、可核对事实、观点路线和媒体说明。正文事实必须能回到这六个入口或来源卡继续记录的一手采访。

- [ ] **Step 2: 固定《小市民系列》候选媒体**

通过 `apps/web/scripts/seed/fetch-source-asset.mjs` 下载并逐张查看：

```text
https://blog.sakugabooru.com/wp-content/uploads/2025/12/oomf1-scaled.jpg
https://blog.sakugabooru.com/wp-content/uploads/2025/12/kanbe-is-in-the-walls-scaled.jpg
https://blog.sakugabooru.com/wp-content/uploads/2025/12/normal-oomf-scaled.jpeg
https://blog.sakugabooru.com/wp-content/uploads/2025/12/oomf-final-scaled.jpg
```

第一张生成 1200×675 封面，其余三张生成最长边不超过 1600 像素的正文 WebP。若某张不对应正文论点，只能从同一来源页已记录的静态剧照中替换，并把最终 URL 写回 `assets.yml`。

- [ ] **Step 3: 固定《百米。》候选媒体**

```text
https://blog.sakugabooru.com/wp-content/uploads/2025/12/hyaku1-scaled.jpg
https://blog.sakugabooru.com/wp-content/uploads/2025/12/hyaku2-scaled.jpg
https://blog.sakugabooru.com/wp-content/uploads/2025/12/hyaku4-scaled.jpg
https://blog.sakugabooru.com/wp-content/uploads/2025/12/hyaku9-scaled.jpg
```

第一张生成封面，其余三张分别服务于身体线条、速度表现和结尾判断。

- [ ] **Step 4: 固定《CITY》候选媒体**

```text
https://blog.sakugabooru.com/wp-content/uploads/2025/09/homo-loser-niikura-defeats-nose-goblin-in-war-of-attrition-scaled.jpg
https://blog.sakugabooru.com/wp-content/uploads/2025/09/rikos-educational-corner-scaled.jpg
https://blog.sakugabooru.com/wp-content/uploads/2025/09/the-springs-in-the-logo-are-also-a-reference-to-the-final-scene-btw.jpeg
https://blog.sakugabooru.com/wp-content/uploads/2025/09/i-think-tokuyama-drew-it-becaus-everyone-has-city-line-thickness.jpg
```

逐张检查人物裁切和清晰度；封面必须呈现城市或群像的运动感，不能把单个人物硬裁成头像式构图。正文图分别服务于喜剧节奏、空间变形和统一线条。

- [ ] **Step 5: 写入哈希并校验第一小批次媒体**

```powershell
node apps/web/scripts/seed/prepare-content-media.mjs --pack content/seed/content-v3 --write-hashes
node apps/web/scripts/seed/prepare-content-media.mjs --pack content/seed/content-v3 --check
```

Expected: 所有新媒体的本地文件、SHA-256、尺寸和内容类型一致，既有 16 个资产继续通过。

### Task 33: 写作第一小批次三篇动漫文章

**Files:**
- Modify: `content/seed/content-v3/manifest.yml`
- Modify: `content/seed/content-v3/posts.yml`
- Create: `content/seed/content-v3/posts/shoushimin-cinematography.md`
- Create: `content/seed/content-v3/posts/hyakuemu-one-hundred-meters.md`
- Create: `content/seed/content-v3/posts/city-restless-town.md`

- [ ] **Step 1: 写《小市民系列》的摄影，为什么总让人坐立不安**

使用 `design-sis`、`feature-review` 和 2,400–3,000 个正文汉字。结构固定为四个无编号运动：过度整齐的日常构图；人物与镜头之间被维持的距离；甜点、房间和视线如何积累威胁；回到“普通”作为表演而非结论。H2 为 2–4 个，标题不得采用《末日后酒店》式文学短句。三张正文图分别放在构图、空间和结尾证据之后。

- [ ] **Step 2: 写一百米够跑完一个人的一生吗？——《百米。》观后**

使用 `anime-critic`、`feature-review` 和 2,400–3,000 个正文汉字。正文从一次具体跑步画面进入，依次讨论线条中的身体重量、岩井泽健治对运动的处理、胜负与时间感，再用保留意见结束；不写完整剧情梗概，不把“奔跑等于人生”当万能升华。三张正文图分别支撑身体、速度和最后一次回望。

- [ ] **Step 3: 写关于《CITY》：京都动画怎样画一座停不下来的城**

使用 `design-sis`、`feature-review` 和 2,400–3,200 个正文汉字。与同作者《小市民系列》错开结构：开头直接列三个喜剧瞬间，中段按“人物动作—背景反应—剪辑节拍”拆解，后段讨论京都动画如何统一荒井切利的线条与团队表现。可以使用小清单或短段落，不采用问题逐层回答的形式。

- [ ] **Step 4: 更新文章清单和阶段规模**

为三个 seed key 写完整 YAML：

```text
shoushimin-cinematography / design-sis / ANIME / feature-review
hyakuemu-one-hundred-meters / anime-critic / ANIME / feature-review
city-restless-town / design-sis / ANIME / feature-review
```

发布时间分别固定为 `2026-06-28T14:17:00Z`、`2026-06-19T10:42:00Z`、`2026-06-03T16:08:00Z`，不使用同一小时或固定间隔。

把 `manifest.yml` 暂时更新为 `expectedPosts: 4`、`expectedCategories: { ANIME: 4 }`，保留《末日后酒店》和 8 个账号。

- [ ] **Step 5: 运行内容扫描和 dry-run**

```powershell
$posts = @(
  'content/seed/content-v3/posts/shoushimin-cinematography.md',
  'content/seed/content-v3/posts/hyakuemu-one-hundred-meters.md',
  'content/seed/content-v3/posts/city-restless-town.md'
)
Select-String -Path $posts -Pattern '本文将|首先|其次|最后|总而言之|真正.*不是.*而是|Sakuga Blog|来源：'
node apps/web/scripts/seed/prepare-content-media.mjs --pack content/seed/content-v3 --check
.\scripts\dev\run-seed.ps1 -Mode content_pack -DryRun
git diff --check
```

Expected: 禁用模式零命中；dry-run 为 `validated`，validation 和 quality warnings/errors 均为空。

- [ ] **Step 6: 提交第一小批次**

```powershell
git add -- content/seed/content-v3/manifest.yml content/seed/content-v3/posts.yml content/seed/content-v3/sources.yml content/seed/content-v3/assets.yml content/seed/content-v3/posts/shoushimin-cinematography.md content/seed/content-v3/posts/hyakuemu-one-hundred-meters.md content/seed/content-v3/posts/city-restless-town.md content/seed/content-v3/assets/covers/shoushimin-cinematography.webp content/seed/content-v3/assets/covers/hyakuemu-one-hundred-meters.webp content/seed/content-v3/assets/covers/city-restless-town.webp content/seed/content-v3/assets/inline/shoushimin-centered-frame.webp content/seed/content-v3/assets/inline/shoushimin-room-distance.webp content/seed/content-v3/assets/inline/shoushimin-ordinary-mask.webp content/seed/content-v3/assets/inline/hyakuemu-body-lines.webp content/seed/content-v3/assets/inline/hyakuemu-rain-sprint.webp content/seed/content-v3/assets/inline/hyakuemu-finish.webp content/seed/content-v3/assets/inline/city-comedy-impact.webp content/seed/content-v3/assets/inline/city-space-elasticity.webp content/seed/content-v3/assets/inline/city-line-language.webp
git diff --cached --check
git commit -m "content: 完成首批制作考察文章"
```

### Task 34: 完成第二小批次的动漫来源与文章

**Files:**
- Modify: `content/seed/content-v3/manifest.yml`
- Modify: `content/seed/content-v3/posts.yml`
- Modify: `content/seed/content-v3/sources.yml`
- Modify: `content/seed/content-v3/assets.yml`
- Create: `content/seed/content-v3/posts/chainsaw-man-reze-rain.md`
- Create: `content/seed/content-v3/posts/orb-unpersuaded-people.md`
- Create: `content/seed/content-v3/assets/covers/chainsaw-man-reze-rain.webp`
- Create: `content/seed/content-v3/assets/covers/orb-unpersuaded-people.webp`
- Create: `content/seed/content-v3/assets/inline/chainsaw-man-reze-gerbera.webp`
- Create: `content/seed/content-v3/assets/inline/chainsaw-man-reze-cafe.webp`
- Create: `content/seed/content-v3/assets/inline/chainsaw-man-reze-rain.webp`
- Create: `content/seed/content-v3/assets/inline/orb-voices.webp`
- Create: `content/seed/content-v3/assets/inline/orb-night-sky.webp`
- Create: `content/seed/content-v3/assets/inline/orb-procession.webp`

- [ ] **Step 1: 建立两篇动漫来源卡**

```text
chainsaw-man-reze-sakuga
  https://blog.sakugabooru.com/2026/01/06/chainsaw-man-reze-the-movie-of-the-moment/
  https://chainsawman.dog/movie_reze/

orb-animation-sakuga
  https://blog.sakugabooru.com/2025/05/09/chi-orb-the-challenge-to-animate-the-uncompromising/
  https://anime-chi.jp/
```

来源卡明确区分可核对事实与文章作者观点；不把来源页的段落顺序复制到中文正文。

- [ ] **Step 2: 固定并查看两组媒体**

《蕾塞篇》候选：

```text
https://blog.sakugabooru.com/wp-content/uploads/2026/01/finally-a-good-fireworks-movie-with-yonezu-scaled.jpeg
https://blog.sakugabooru.com/wp-content/uploads/2026/01/white-gerbera-symbolizing-hope-surely-a-happy-movie-scaled.jpg
https://blog.sakugabooru.com/wp-content/uploads/2026/01/nothing-sad-is-about-to-happen-i-promise-scaled.jpg
https://i.imgur.com/eOJMdQ6.jpg
```

《地。》候选：

```text
https://blog.sakugabooru.com/wp-content/uploads/2025/05/orb2.jpg
https://blog.sakugabooru.com/wp-content/uploads/2025/05/orb3.jpg
https://blog.sakugabooru.com/wp-content/uploads/2025/05/so-true-bestie.jpeg
https://blog.sakugabooru.com/wp-content/uploads/2025/05/orb5.jpg
```

每篇选择一张 1200×675 封面和三张正文图；蕾塞封面优先雨、夜色或烟火氛围，《地。》封面优先人物与天空的关系。逐张查看后才能写入 `assets.yml`。

- [ ] **Step 3: 写蕾塞篇：那场雨下得实在太久了**

使用 `moyu-master`、`feature-review` 和 2,000–2,600 个正文汉字。以社区口语写三至五个具体画面，允许直接表达喜欢与不满；不按制作人员履历组织，不写“电影成功之处有三点”，结尾停在电话亭、咖啡馆或雨的记忆之一。

文章定义使用 `ANIME`，发布时间固定为 `2026-05-24T12:51:00Z`。

- [ ] **Step 4: 写我喜欢《地。》里那些没有被说服的人**

使用 `indie-dev`、`feature-review` 和 2,000–2,600 个正文汉字。从配音或音乐进入，选择三组“没有被说服”的人物时刻，讨论声音如何保留固执和犹疑；制作信息只服务于听觉判断，不列名单，不写剧情全解。

文章定义使用 `ANIME`，发布时间固定为 `2026-05-08T09:36:00Z`。

- [ ] **Step 5: 更新清单并验证**

把 `manifest.yml` 更新为 `expectedPosts: 6`、`expectedCategories: { ANIME: 6 }`。运行媒体 `--write-hashes`、`--check`、禁用句式扫描和 content-pack dry-run；期望全部通过且无 warning/error。

### Task 35: 写作真实 Redisson 故障复盘

**Files:**
- Modify: `content/seed/content-v3/manifest.yml`
- Modify: `content/seed/content-v3/posts.yml`
- Modify: `content/seed/content-v3/sources.yml`
- Modify: `content/seed/content-v3/assets.yml`
- Create: `content/seed/content-v3/posts/redisson-moved-timeout.md`
- Create: `content/seed/content-v3/assets/covers/redisson-moved-timeout.webp`
- Create: `content/seed/content-v3/assets/inline/redisson-moved-log.webp`
- Create: `content/seed/content-v3/assets/inline/redisson-cluster-route.webp`
- Temporary: `.codex-tmp/seed-content-v3/technical/redisson-5972-*`

- [ ] **Step 1: 固定技术来源与事实边界**

来源卡 `redisson-5972-moved-timeout` 使用：

```text
https://github.com/redisson/redisson/issues/5972
https://api.github.com/repos/redisson/redisson/issues/5972
https://github.com/redisson/redisson/milestone/178
```

事实锚点固定为 Redis 6.0.11、Redisson 3.30.0、三主三从切换、客户端拓扑未及时刷新、`MOVED`、`Unable to acquire connection`、重启暂时恢复、Issue 关闭并归入 3.33.0 milestone。正文必须明确这是公开 Issue 的排障复盘，不冒充作者本人生产事故。

- [ ] **Step 2: 生成可核对的技术媒体**

封面使用 GitHub Issue 标题、状态与 milestone 的浏览器截图，裁掉用户名和无关导航。正文第一张图把 Issue 中公开日志按原顺序排版为终端截图；第二张图使用 HTML/CSS 绘制三节点路由示意，只标注 `client topology`、`old master`、`new master`、`MOVED`，不得做装饰性信息图。所有生成输入保存在 `.codex-tmp`，正式只提交 WebP 和来源元数据。

- [ ] **Step 3: 写 Redis 明明还活着，为什么请求还是一批批超时**

使用 `night-coder`、`technical-report` 和 1,800–2,400 个正文汉字。结构为：先贴最小日志；解释为什么“加连接池”是第一误判；从 `NodeSource` 和 `redirect=MOVED` 找到拓扑线索；还原主从切换后的请求路径；说明重启为何只暂时有效；以 3.33.0 milestone 和升级验证结束。不得把未在 Issue 中出现的参数、监控数据或修复 commit 写成事实。

文章定义使用 `TECH`，发布时间固定为 `2026-04-27T15:22:00Z`。

- [ ] **Step 4: 更新清单、验证并提交第二小批次**

把 `manifest.yml` 更新为 `expectedPosts: 7`，分类为 `ANIME: 6`、`TECH: 1`。运行目标测试、媒体检查、dry-run 和 `git diff --check`，然后提交：

```powershell
git add -- content/seed/content-v3/manifest.yml content/seed/content-v3/posts.yml content/seed/content-v3/sources.yml content/seed/content-v3/assets.yml content/seed/content-v3/posts/chainsaw-man-reze-rain.md content/seed/content-v3/posts/orb-unpersuaded-people.md content/seed/content-v3/posts/redisson-moved-timeout.md content/seed/content-v3/assets/covers/chainsaw-man-reze-rain.webp content/seed/content-v3/assets/covers/orb-unpersuaded-people.webp content/seed/content-v3/assets/covers/redisson-moved-timeout.webp content/seed/content-v3/assets/inline/chainsaw-man-reze-gerbera.webp content/seed/content-v3/assets/inline/chainsaw-man-reze-cafe.webp content/seed/content-v3/assets/inline/chainsaw-man-reze-rain.webp content/seed/content-v3/assets/inline/orb-voices.webp content/seed/content-v3/assets/inline/orb-night-sky.webp content/seed/content-v3/assets/inline/orb-procession.webp content/seed/content-v3/assets/inline/redisson-moved-log.webp content/seed/content-v3/assets/inline/redisson-cluster-route.webp
git diff --cached --check
git commit -m "content: 完成第二批观后感与后端复盘"
```

提交前用 `git diff --cached --name-only` 确认只包含本任务两篇动漫、Redisson 文章、对应 YAML 与媒体；不得包含 `.codex-tmp`。

### Task 36: 运行可重复的 RAG 切片实验并成文

**Files:**
- Modify: `content/seed/content-v3/manifest.yml`
- Modify: `content/seed/content-v3/posts.yml`
- Modify: `content/seed/content-v3/sources.yml`
- Modify: `content/seed/content-v3/assets.yml`
- Create: `content/seed/content-v3/posts/rag-chunk-size-regression.md`
- Create: `content/seed/content-v3/assets/covers/rag-chunk-size-regression.webp`
- Create: `content/seed/content-v3/assets/inline/rag-recall-table.webp`
- Create: `content/seed/content-v3/assets/inline/rag-failed-query.webp`
- Temporary: `.codex-tmp/seed-content-v3/technical/rag_chunk_experiment.py`
- Temporary: `.codex-tmp/seed-content-v3/technical/rag_chunk_results-a.json`
- Temporary: `.codex-tmp/seed-content-v3/technical/rag_chunk_results-b.json`

- [ ] **Step 1: 固定实验语料和问题集**

语料只读取仓库中以下文件，按 Markdown 标题保留来源路径：

```text
README.md
AGENTS.md
apps/server/README.md
apps/server/src/main/resources/knowledge/about-me.md
apps/server/src/main/resources/knowledge/my-home.md
apps/server/src/main/resources/knowledge/my-world.md
apps/server/src/main/resources/knowledge/those-stories.md
```

固定八个查询：站长用户 ID 如何配置、通知为何不用 Kafka、Agent 对话记忆存在哪里、搜索使用什么分词、文章缓存有哪些层、Bangumi 数据如何同步、OSS 上传怎样鉴权、项目为何不用 Neo4j。每个查询在脚本中写出期望命中的文件和标题。

- [ ] **Step 2: 编写无外部模型依赖的基线脚本**

脚本使用 Python 标准库读取 Markdown，以汉字和英文 token 组成简单 TF-IDF 余弦检索；分别测试 `chunk_size=500, overlap=80` 与 `chunk_size=800, overlap=80`，输出每个问题 top-3、Hit@1 和 MRR。固定排序规则为分数降序后按 `source_path + chunk_index` 升序，保证重复运行结果一致。

- [ ] **Step 3: 执行两次并确认确定性**

```powershell
python .codex-tmp/seed-content-v3/technical/rag_chunk_experiment.py --output .codex-tmp/seed-content-v3/technical/rag_chunk_results-a.json
python .codex-tmp/seed-content-v3/technical/rag_chunk_experiment.py --output .codex-tmp/seed-content-v3/technical/rag_chunk_results-b.json
Get-FileHash .codex-tmp/seed-content-v3/technical/rag_chunk_results-a.json
Get-FileHash .codex-tmp/seed-content-v3/technical/rag_chunk_results-b.json
```

Expected: 两个 SHA-256 相同。若 800 并未比 500 更差，标题和立场必须跟随真实结果修改，禁止调换标签或删问题制造结论。

- [ ] **Step 4: 写把切片从 500 改到 800 以后，检索反而更差了**

使用 `algo-runner`、`technical-report` 和 1,800–2,600 个正文汉字。正文公开语料、切片、检索算法、八个问题、Hit@1/MRR 和失败样例；明确 TF-IDF 只是无模型基线，不把结果外推到所有 embedding 或 RAG 系统。封面使用真实结果表，正文图展示指标和一个失败查询。

文章定义使用 `TECH`，发布时间固定为 `2026-04-11T11:05:00Z`。

- [ ] **Step 5: 更新来源与媒体元数据**

来源卡记录七个本地文件的 Git commit、实验脚本 SHA-256、结果 JSON SHA-256 和执行时间。媒体由同一结果 JSON 生成；不得手工改图中数值。

### Task 37: 完成游戏体验帖

**Files:**
- Modify: `content/seed/content-v3/posts.yml`
- Modify: `content/seed/content-v3/sources.yml`
- Modify: `content/seed/content-v3/assets.yml`
- Create: `content/seed/content-v3/posts/urban-myth-search-box.md`
- Create: `content/seed/content-v3/assets/covers/urban-myth-search-box.webp`
- Create: `content/seed/content-v3/assets/inline/urban-myth-search-interface.webp`
- Create: `content/seed/content-v3/assets/inline/urban-myth-dialogue.webp`
- Create: `content/seed/content-v3/assets/inline/urban-myth-investigation.webp`

- [ ] **Step 1: 建立官方来源卡并固定截图**

```text
https://umdc.shueisha-games.com/
https://shueisha-games.com/games/umdc/
https://store.steampowered.com/app/2089600/Urban_Myth_Dissolution_Center/?l=schinese
```

只使用官网媒体包、官方页面截图或 Steam 商店官方截图；至少固定一张搜索界面、一张角色对话和一张案件调查画面。避免使用会直接泄露最终谜底的截图。

- [ ] **Step 2: 写《都市传说解体中心》通关后，我还是不敢关掉搜索框**

使用 `moyu-master`、现有 `review` 格式和 1,600–2,200 个正文汉字。开头直接写搜索框和时间线操作，中段讨论网络传言怎样成为推理界面，再写一处喜欢与一处不满；采用低剧透表述，不冒充攻略，不虚构购买平台或通关日期。

文章定义使用 `GAME`，发布时间固定为 `2026-03-29T13:48:00Z`。

- [ ] **Step 3: 检查同作者差异**

对比 `chainsaw-man-reze-rain.md`：标题以外的首段五字片段 Jaccard 不得超过质量门阈值；两篇不能都用雨、夜色或“关掉页面后仍然想起”收束。

### Task 38: 完成《横道世之介》阅读随笔

**Files:**
- Modify: `content/seed/content-v3/posts.yml`
- Modify: `content/seed/content-v3/sources.yml`
- Create: `content/seed/content-v3/posts/yokomichi-yonosuke-after.md`

- [ ] **Step 1: 建立书目信息卡**

来源卡 `yokomichi-yonosuke-book` 固定记录吉田修一、上海人民出版社 2018 年版、译者林雅惠和 ISBN `9787208152038`，书目信息入口为 `https://read.douban.com/ebook/54232408/`。书目信息只用于核对人物和版本，不复制书评或大段原文。该文 `coverAsset: null`、`inlineAssets: []`，不用无关书桌图补位。

- [ ] **Step 2: 写横道世之介离开以后，书里反而更热闹了**

使用 `book-notes`、`personal-essay` 和 1,000–1,600 个正文汉字。只围绕世之介离场后其他人物记忆中的变化写作；允许提及一两个情节事实，但不复述故事，不引用长段原文，不把人物死亡写成人生励志结论。全文最多两个 H2，也可以完全不用标题。

文章定义使用 `LIFE`，发布时间固定为 `2026-03-12T07:19:00Z`。

- [ ] **Step 3: 完成十篇内容清单**

把 `manifest.yml` 更新为：

```yaml
stage: review
expectedAccounts: 8
expectedPosts: 10
expectedCategories:
  ANIME: 6
  TECH: 2
  GAME: 1
  LIFE: 1
```

当前内容包的分类字段是非空字符串且不受 Java 枚举限制，本批阅读随笔固定使用 `LIFE`；不得为本任务新增数据库分类表或枚举。

- [ ] **Step 4: 验证并提交第三小批次**

```powershell
node apps/web/scripts/seed/prepare-content-media.mjs --pack content/seed/content-v3 --write-hashes
node apps/web/scripts/seed/prepare-content-media.mjs --pack content/seed/content-v3 --check
.\scripts\dev\run-seed.ps1 -Mode content_pack -DryRun
git diff --check
```

Expected: `expectedPosts=10`，分类总数为 10，报告 `status=validated` 且 validation/quality/index 错误为空。随后只暂存 Task 36–38 的正式文件并提交：

```powershell
git add -- content/seed/content-v3/manifest.yml content/seed/content-v3/posts.yml content/seed/content-v3/sources.yml content/seed/content-v3/assets.yml content/seed/content-v3/posts/rag-chunk-size-regression.md content/seed/content-v3/posts/urban-myth-search-box.md content/seed/content-v3/posts/yokomichi-yonosuke-after.md content/seed/content-v3/assets/covers/rag-chunk-size-regression.webp content/seed/content-v3/assets/covers/urban-myth-search-box.webp content/seed/content-v3/assets/inline/rag-recall-table.webp content/seed/content-v3/assets/inline/rag-failed-query.webp content/seed/content-v3/assets/inline/urban-myth-search-interface.webp content/seed/content-v3/assets/inline/urban-myth-dialogue.webp content/seed/content-v3/assets/inline/urban-myth-investigation.webp
git diff --cached --check
git commit -m "content: 完成第三批实验与社区随笔"
```

### Task 39: 生成十篇文章的可视化审阅页

**Files:**
- Replace temporarily: `.codex-tmp/seed-content-v3/review/index.html`
- Create temporarily: `.codex-tmp/seed-content-v3/review/posts/<slug>.html`
- Create temporarily: `.codex-tmp/seed-content-v3/review/coverage.json`

- [ ] **Step 1: 生成总览页**

总览包含十张文章卡：真实作者头像、昵称、标题、分类、正文汉字数、封面和文章链接。卡片顺序按 `publishTime`，不能按类别分组，以便检查真实首页观感。无封面的阅读随笔使用纯文字卡，不生成默认占位图。

- [ ] **Step 2: 生成九篇新增文章全文页**

每页使用与《末日后酒店》一致的站点阅读宽度，但保留文章各自的标题数量、段落节奏和媒体布局。页面不显示来源面板、内部 seed key 或质量扫描结果。

- [ ] **Step 3: 运行浏览器验证**

检查总览页、九篇全文页、8 个头像和全部新媒体返回 HTTP 200；所有 `<img>` 的 `naturalWidth > 0`；浏览器 console error 和 page error 均为 0。把结果写入 `coverage.json`，包括页面数、图片数、失败 URL、各篇汉字数和 H2 数量。

- [ ] **Step 4: 使用可视化辅助做封面总览检查**

在已获用户同意的 visual companion 中展示十张封面/文字卡的总览，不要求用户逐篇确认；此步骤用于发现封面构图、标题句式或作者分布的批量感。发现问题时回到对应文章或媒体任务修正，并重新生成新文件名的总览页。

### Task 40: 完成整批质量审查与最终验证

**Files:**
- Modify if a finding targets it: `content/seed/content-v3/manifest.yml`
- Modify if a finding targets it: `content/seed/content-v3/posts.yml`
- Modify if a finding targets it: `content/seed/content-v3/sources.yml`
- Modify if a finding targets it: `content/seed/content-v3/assets.yml`
- Modify if a finding targets it: `content/seed/content-v3/posts/shoushimin-cinematography.md`
- Modify if a finding targets it: `content/seed/content-v3/posts/hyakuemu-one-hundred-meters.md`
- Modify if a finding targets it: `content/seed/content-v3/posts/city-restless-town.md`
- Modify if a finding targets it: `content/seed/content-v3/posts/chainsaw-man-reze-rain.md`
- Modify if a finding targets it: `content/seed/content-v3/posts/orb-unpersuaded-people.md`
- Modify if a finding targets it: `content/seed/content-v3/posts/redisson-moved-timeout.md`
- Modify if a finding targets it: `content/seed/content-v3/posts/rag-chunk-size-regression.md`
- Modify if a finding targets it: `content/seed/content-v3/posts/urban-myth-search-box.md`
- Modify if a finding targets it: `content/seed/content-v3/posts/yokomichi-yonosuke-after.md`

- [ ] **Step 1: 运行定向测试**

```powershell
cd apps/server
mvn -q '-Dtest=ContentPackValidatorTest,ContentPackQualityGateTest,ContentPackLoaderTest,ContentPackDatabaseWriterTest,ContentPackImportServiceTest' test
cd ../..
```

Expected: 全部测试通过，无 failure/error；条件性集成项允许按原规则 skipped。

- [ ] **Step 2: 运行媒体和只读导入验证**

```powershell
node apps/web/scripts/seed/prepare-content-media.mjs --pack content/seed/content-v3 --check
.\scripts\dev\run-seed.ps1 -Mode content_pack -DryRun
git diff --check
```

Expected: 媒体全通过；报告为 `validated`，`validationWarnings`、`qualityWarnings`、`qualityErrors`、`indexFailures` 全为空。

- [ ] **Step 3: 运行跨文编辑扫描**

逐篇检查标题句式、首段、末段、H2 数量和正文长度分布；五篇新增动漫文章不得共享“意象句＋破折号＋作品名”模板，同作者两篇不得共享收束方式。扫描以下模式并逐条人工判定，正文零保留模板命中：

```text
本文将|首先|其次|最后|综上|总而言之|不难发现|值得一提|真正.*不是.*而是|这意味着|由此可见|让我们
```

- [ ] **Step 4: 做事实与来源抽查**

每篇至少随机抽取五个事实锚点，回到 `sources.yml` 指向的页面核对。技术文章逐项核对版本、参数、日志和实验 JSON；动漫文章核对人物、集数、制作人员和图片画面。无法核实的句子删除或降格为明确个人判断。

- [ ] **Step 5: 提交最终校验修正**

只有正式内容文件发生修正时才创建提交：

```powershell
git add -- content/seed/content-v3/manifest.yml content/seed/content-v3/posts.yml content/seed/content-v3/sources.yml content/seed/content-v3/assets.yml content/seed/content-v3/posts/shoushimin-cinematography.md content/seed/content-v3/posts/hyakuemu-one-hundred-meters.md content/seed/content-v3/posts/city-restless-town.md content/seed/content-v3/posts/chainsaw-man-reze-rain.md content/seed/content-v3/posts/orb-unpersuaded-people.md content/seed/content-v3/posts/redisson-moved-timeout.md content/seed/content-v3/posts/rag-chunk-size-regression.md content/seed/content-v3/posts/urban-myth-search-box.md content/seed/content-v3/posts/yokomichi-yonosuke-after.md content/seed/content-v3/assets/covers/shoushimin-cinematography.webp content/seed/content-v3/assets/covers/hyakuemu-one-hundred-meters.webp content/seed/content-v3/assets/covers/city-restless-town.webp content/seed/content-v3/assets/covers/chainsaw-man-reze-rain.webp content/seed/content-v3/assets/covers/orb-unpersuaded-people.webp content/seed/content-v3/assets/covers/redisson-moved-timeout.webp content/seed/content-v3/assets/covers/rag-chunk-size-regression.webp content/seed/content-v3/assets/covers/urban-myth-search-box.webp content/seed/content-v3/assets/inline/shoushimin-centered-frame.webp content/seed/content-v3/assets/inline/shoushimin-room-distance.webp content/seed/content-v3/assets/inline/shoushimin-ordinary-mask.webp content/seed/content-v3/assets/inline/hyakuemu-body-lines.webp content/seed/content-v3/assets/inline/hyakuemu-rain-sprint.webp content/seed/content-v3/assets/inline/hyakuemu-finish.webp content/seed/content-v3/assets/inline/city-comedy-impact.webp content/seed/content-v3/assets/inline/city-space-elasticity.webp content/seed/content-v3/assets/inline/city-line-language.webp content/seed/content-v3/assets/inline/chainsaw-man-reze-gerbera.webp content/seed/content-v3/assets/inline/chainsaw-man-reze-cafe.webp content/seed/content-v3/assets/inline/chainsaw-man-reze-rain.webp content/seed/content-v3/assets/inline/orb-voices.webp content/seed/content-v3/assets/inline/orb-night-sky.webp content/seed/content-v3/assets/inline/orb-procession.webp content/seed/content-v3/assets/inline/redisson-moved-log.webp content/seed/content-v3/assets/inline/redisson-cluster-route.webp content/seed/content-v3/assets/inline/rag-recall-table.webp content/seed/content-v3/assets/inline/rag-failed-query.webp content/seed/content-v3/assets/inline/urban-myth-search-interface.webp content/seed/content-v3/assets/inline/urban-myth-dialogue.webp content/seed/content-v3/assets/inline/urban-myth-investigation.webp apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackQualityGate.java apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackQualityGateTest.java
git diff --cached --check
git commit -m "fix: 完成首批扩写内容质量校验"
```

不得暂存 `.codex-tmp` 或 `.superpowers`。不执行正式 seed，不推送分支。

- [ ] **Step 6: 交付用户审阅并停止**

确认 `git status --short` 为空、暂存区为空、审阅服务仍可访问。向用户提供十篇总览 URL 和九篇全文入口，等待用户逐篇反馈；用户批准前不进入互动设计和余下约三十篇扩写。
