# Seed Content Pack Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current low-quality Seed community with 8 natural-looking accounts, 40 curated illustrated posts, and reproducible non-uniform interactions without changing existing Seed user IDs or the IDs of the 32 existing Seed posts.

**Architecture:** A versioned `content/seed/content-v2` package becomes the source of truth. Focused loader, validator, identity resolver, media publisher, database writer, and import orchestrator components apply the package idempotently; MySQL remains the runtime source of truth, StorageService owns stable media URLs, and SearchIndexService is synchronized after database commit.

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
