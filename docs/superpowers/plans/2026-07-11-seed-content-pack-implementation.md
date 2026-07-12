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
