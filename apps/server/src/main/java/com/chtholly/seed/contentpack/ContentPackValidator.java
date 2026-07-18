package com.chtholly.seed.contentpack;

import com.chtholly.seed.contentpack.model.ContentPack;
import com.chtholly.seed.contentpack.model.ContentPackManifest;
import com.chtholly.seed.contentpack.model.SeedAccountDefinition;
import com.chtholly.seed.contentpack.model.SeedAssetDefinition;
import com.chtholly.seed.contentpack.model.SeedCommentDefinition;
import com.chtholly.seed.contentpack.model.SeedFollowDefinition;
import com.chtholly.seed.contentpack.model.SeedPostDefinition;
import com.chtholly.seed.contentpack.model.SeedPostRetirementDefinition;
import com.chtholly.seed.contentpack.model.SeedReactionDefinition;
import com.chtholly.seed.contentpack.model.SeedSourceDefinition;
import com.chtholly.seed.contentpack.model.SeedViewDefinition;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Validates a loaded content pack before any persistence boundary is entered.
 */
@Component
public final class ContentPackValidator {

    static final String SITE_OWNER_AUTHOR = "site-owner";
    private static final Pattern HANDLE_PATTERN = Pattern.compile("[A-Za-z0-9_]{3,64}");
    private static final Set<String> STAGES = Set.of("review", "complete");
    private static final Set<String> REACTION_TYPES = Set.of("like", "fav");
    private static final int MAX_COMMENTS_PER_TARGET = 5;
    private static final Pattern URI_SCHEME_PATTERN = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*):");

    /**
     * Collects every structural error and rejects the pack once with deterministic diagnostics.
     *
     * @param pack fully loaded filesystem content pack
     * @return non-blocking validation warnings
     * @throws IllegalArgumentException when one or more structural errors are present
     */
    public ValidationResult validate(ContentPack pack) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        validateManifest(pack, errors);
        validateAccounts(pack, errors);
        validateAssets(pack, errors);
        validateSources(pack, errors);
        validatePosts(pack, errors);
        validateRetirements(pack, errors);
        validateComments(pack, errors);
        validateReactions(pack, errors);
        validateFollows(pack, errors);
        validateViews(pack, errors);

        if (!errors.isEmpty()) {
            // 一次返回全部问题，避免 dry-run 修一个错误后才能看到下一个错误。
            throw new IllegalArgumentException("Content pack validation failed:\n" + String.join("\n", errors));
        }
        return new ValidationResult(warnings);
    }

    private void validateManifest(ContentPack pack, List<String> errors) {
        ContentPackManifest manifest = pack.manifest();
        if (manifest == null) {
            errors.add("missing manifest");
            return;
        }
        requireNonblank(manifest.version(), "blank manifest version", errors);
        requireNonblank(manifest.namespace(), "blank manifest namespace", errors);
        if (!STAGES.contains(manifest.stage())) {
            errors.add("invalid manifest stage: " + manifest.stage());
        }
        if (manifest.expectedAccounts() != pack.accounts().size()) {
            errors.add("expected account count: " + manifest.expectedAccounts() + ", actual: " + pack.accounts().size());
        }
        if (manifest.expectedPosts() != pack.posts().size()) {
            errors.add("expected post count: " + manifest.expectedPosts() + ", actual: " + pack.posts().size());
        }
        if (manifest.expectedRetirements() != pack.retirements().size()) {
            errors.add("expected retirement count: " + manifest.expectedRetirements()
                    + ", actual: " + pack.retirements().size());
        }
        if (!pack.retirements().isEmpty() && !"complete".equals(manifest.stage())) {
            errors.add("retirements require complete stage");
        }

        boolean declarationsRequired = isContentV3(pack);
        long rootComments = pack.comments().stream()
                .filter(comment -> isBlank(comment.parentSeedKey()))
                .count();
        long replies = pack.comments().size() - rootComments;
        long likes = pack.reactions().stream()
                .filter(reaction -> "like".equals(reaction.type()))
                .count();
        long favorites = pack.reactions().stream()
                .filter(reaction -> "fav".equals(reaction.type()))
                .count();
        Set<String> postSeedKeys = keys(pack.posts(), SeedPostDefinition::seedKey);
        long commentedTargets = pack.comments().stream()
                .map(comment -> validCommentTarget(comment, postSeedKeys))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .count();
        validateExpectedCount(manifest.expectedComments(), pack.comments().size(), "comment",
                declarationsRequired, errors);
        validateExpectedCount(manifest.expectedRootComments(), rootComments, "root comment",
                declarationsRequired, errors);
        validateExpectedCount(manifest.expectedReplies(), replies, "reply", declarationsRequired, errors);
        validateExpectedCount(manifest.expectedLikes(), likes, "like", declarationsRequired, errors);
        validateExpectedCount(manifest.expectedFavorites(), favorites, "favorite", declarationsRequired, errors);
        validateExpectedCount(manifest.expectedFollows(), pack.follows().size(), "follow",
                declarationsRequired, errors);
        validateExpectedCount(manifest.expectedViews(), pack.views().size(), "view", declarationsRequired, errors);
        validateExpectedCount(manifest.expectedCommentedTargets(), commentedTargets, "commented target",
                declarationsRequired, errors);

        Map<String, Integer> actualCategories = new LinkedHashMap<>();
        for (SeedPostDefinition post : pack.posts()) {
            if (post.category() != null && !post.category().isBlank()) {
                actualCategories.merge(post.category(), 1, Integer::sum);
            }
        }
        Map<String, Integer> expectedCategories = manifest.expectedCategories();
        if (!actualCategories.equals(expectedCategories)) {
            errors.add("expected category counts: " + new TreeMap<>(expectedCategories)
                    + ", actual: " + new TreeMap<>(actualCategories));
        }
    }

    private void validateAccounts(ContentPack pack, List<String> errors) {
        addDuplicates(pack.accounts(), SeedAccountDefinition::seedKey, "duplicate account seedKey: ", errors);
        // MySQL 使用 utf8mb4_unicode_ci；至少先按 Locale.ROOT 折叠大小写，避免校验通过后写库撞唯一键。
        addDuplicates(pack.accounts(), SeedAccountDefinition::handle, this::foldDatabaseIdentifier,
                "duplicate account handle: ", errors);
        for (SeedAccountDefinition account : pack.accounts()) {
            if (SITE_OWNER_AUTHOR.equals(account.seedKey())) {
                errors.add("reserved account seedKey: " + SITE_OWNER_AUTHOR);
            }
            if (account.handle() == null || !HANDLE_PATTERN.matcher(account.handle()).matches()) {
                errors.add("invalid account handle: " + account.seedKey());
            }
            if (!pack.assets().containsKey(account.avatarAsset())) {
                errors.add("missing avatar asset: " + account.seedKey() + " -> " + account.avatarAsset());
            }
            if (isContentV3(pack)) {
                if (account.bio() == null || account.bio().isBlank()) {
                    errors.add("missing account bio: " + account.seedKey());
                }
                if (account.tags() == null || account.tags().size() < 2 || account.tags().size() > 4) {
                    errors.add("account tags must contain 2 to 4 values: " + account.seedKey());
                }
                if (account.joinedAt() == null) {
                    errors.add("missing account joinedAt: " + account.seedKey());
                }
            }
        }
    }

    private void validatePosts(ContentPack pack, List<String> errors) {
        addDuplicates(pack.posts(), SeedPostDefinition::seedKey, "duplicate post seedKey: ", errors);
        // Slug 的唯一索引同样采用大小写不敏感 collation。
        addDuplicates(pack.posts(), SeedPostDefinition::slug, this::foldDatabaseIdentifier,
                "duplicate post slug: ", errors);
        Set<String> accountKeys = keys(pack.accounts(), SeedAccountDefinition::seedKey);
        for (SeedPostDefinition post : pack.posts()) {
            if (!isDeclaredPostAuthor(accountKeys, post.authorSeedKey())) {
                errors.add("missing post author: " + post.seedKey() + " -> " + post.authorSeedKey());
            }
            if (post.coverAsset() != null && !post.coverAsset().isBlank()
                    && !pack.assets().containsKey(post.coverAsset())) {
                errors.add("missing cover asset: " + post.seedKey() + " -> " + post.coverAsset());
            }
            for (String inlineAsset : post.inlineAssets()) {
                if (!pack.assets().containsKey(inlineAsset)) {
                    errors.add("missing inline asset: " + post.seedKey() + " -> " + inlineAsset);
                }
            }
            if (post.markdown() == null || post.markdown().isBlank()) {
                errors.add("blank Markdown: " + post.seedKey());
            }
            if (post.category() == null || post.category().isBlank()) {
                errors.add("missing post category: " + post.seedKey());
            }
            int descriptionLength = post.description() == null ? 0 : post.description().codePointCount(0, post.description().length());
            if (descriptionLength < 10 || descriptionLength > 50) {
                errors.add("invalid description length: " + post.seedKey() + " -> " + descriptionLength);
            }
            validatePath(pack.root(), post.seedKey(), post.markdownFile(), errors);
            if (post.publishTime() == null) {
                errors.add("missing timestamp: post " + post.seedKey());
            }
            if (isContentV3(pack)) {
                validatePostSources(pack, post, errors);
            }
        }
    }

    private boolean isDeclaredPostAuthor(Set<String> accountKeys, String authorSeedKey) {
        return SITE_OWNER_AUTHOR.equals(authorSeedKey) || accountKeys.contains(authorSeedKey);
    }

    private void validateRetirements(ContentPack pack, List<String> errors) {
        Set<String> contentPackSlugs = new HashSet<>();
        for (SeedPostDefinition post : pack.posts()) {
            contentPackSlugs.add(foldDatabaseIdentifier(post.slug()));
        }
        Set<String> seen = new HashSet<>();
        Set<String> duplicateReports = new HashSet<>();
        for (SeedPostRetirementDefinition retirement : pack.retirements()) {
            String slug = retirement.slug();
            if (slug == null || slug.isBlank()) {
                errors.add("blank retirement slug");
                continue;
            }
            if (slug.codePointCount(0, slug.length()) > 128) {
                errors.add("retirement slug exceeds 128 code points: " + slug);
            }
            String folded = foldDatabaseIdentifier(slug);
            if (!seen.add(folded) && duplicateReports.add(folded)) {
                errors.add("duplicate retirement slug: " + slug);
            }
            if (contentPackSlugs.contains(folded)) {
                errors.add("retirement slug overlaps content-pack post: " + slug);
            }
        }
    }

    private void validateAssets(ContentPack pack, List<String> errors) {
        for (SeedAssetDefinition asset : pack.assets().values()) {
            if (containsForbiddenAssetMarker(asset.source())
                    || containsForbiddenAssetMarker(asset.sourceUrl())
                    || containsForbiddenAssetMarker(asset.sourcePageUrl())
                    || containsForbiddenAssetMarker(asset.sourceFile())
                    || containsForbiddenAssetMarker(asset.usageNote())) {
                errors.add("AI-generated asset forbidden: " + asset.key());
            }
            HttpUrlStatus sourceUrlStatus = httpUrlStatus(asset.sourceUrl());
            if (sourceUrlStatus == HttpUrlStatus.INVALID) {
                errors.add("invalid asset source URL: " + asset.key());
            }
            boolean webAsset = "public-web".equalsIgnoreCase(trim(asset.source()))
                    || sourceUrlStatus != HttpUrlStatus.NOT_HTTP;
            if (isContentV3(pack) && webAsset) {
                requireNonblank(asset.sourcePageUrl(), "missing asset source page: " + asset.key(), errors);
                if (asset.sourcePageUrl() != null && !asset.sourcePageUrl().isBlank()
                        && httpUrlStatus(asset.sourcePageUrl()) != HttpUrlStatus.VALID) {
                    errors.add("invalid asset source page URL: " + asset.key());
                }
                if (asset.fetchedAt() == null) {
                    errors.add("missing asset fetched timestamp: " + asset.key());
                }
                requireNonblank(asset.usageNote(), "missing asset usage note: " + asset.key(), errors);
            }
        }
    }

    private void validateSources(ContentPack pack, List<String> errors) {
        if (!isContentV3(pack)) {
            return;
        }
        for (SeedSourceDefinition source : pack.sources().values()) {
            String key = source.key();
            if (key == null || key.isBlank()) {
                errors.add("blank source key");
            }
            requireNonblank(source.type(), "blank source type: " + key, errors);
            requireNonblank(source.title(), "blank source title: " + key, errors);
            if (source.pageUrl() == null || source.pageUrl().isBlank()) {
                errors.add("missing source page URL: " + key);
            } else if (httpUrlStatus(source.pageUrl()) != HttpUrlStatus.VALID) {
                errors.add("invalid source page URL: " + key);
            }
            if (source.fetchedAt() == null) {
                errors.add("missing source fetched timestamp: " + key);
            }
            requireNonblank(source.usageNote(), "missing source usage note: " + key, errors);
            if (source.factAnchors().isEmpty()) {
                errors.add("missing source fact anchors: " + key);
            }
        }
    }

    private void validatePostSources(ContentPack pack, SeedPostDefinition post, List<String> errors) {
        List<String> sources = post.brief() == null ? List.of() : post.brief().sources();
        if (sources.isEmpty()) {
            errors.add("missing post source: " + post.seedKey());
            return;
        }
        for (String source : sources) {
            if (!pack.sources().containsKey(source)) {
                errors.add("missing post source: " + post.seedKey() + " -> " + source);
            }
        }
    }

    private boolean containsForbiddenAssetMarker(String value) {
        if (value == null) {
            return false;
        }
        StringBuilder normalized = new StringBuilder();
        value.codePoints()
                .filter(Character::isLetterOrDigit)
                .map(Character::toLowerCase)
                .forEach(normalized::appendCodePoint);
        return normalized.indexOf("openaiimagegen") >= 0
                || normalized.indexOf("generated") >= 0
                || normalized.indexOf("gocrazyai") >= 0;
    }

    private HttpUrlStatus httpUrlStatus(String value) {
        if (value == null || value.isBlank()) {
            return HttpUrlStatus.NOT_HTTP;
        }
        String normalized = value.strip();
        var matcher = URI_SCHEME_PATTERN.matcher(normalized);
        if (!matcher.find()) {
            return HttpUrlStatus.NOT_HTTP;
        }
        String scheme = matcher.group(1);
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            return HttpUrlStatus.NOT_HTTP;
        }
        try {
            URI uri = new URI(normalized);
            return uri.getHost() == null || uri.getHost().isBlank()
                    ? HttpUrlStatus.INVALID : HttpUrlStatus.VALID;
        } catch (URISyntaxException exception) {
            return HttpUrlStatus.INVALID;
        }
    }

    private String trim(String value) {
        return value == null ? null : value.strip();
    }

    private boolean isContentV3(ContentPack pack) {
        return pack.manifest() != null && "content-v3".equals(pack.manifest().version());
    }

    private enum HttpUrlStatus {
        NOT_HTTP,
        VALID,
        INVALID
    }

    private void validateComments(ContentPack pack, List<String> errors) {
        addDuplicates(pack.comments(), SeedCommentDefinition::seedKey, "duplicate comment seedKey: ", errors);
        Map<String, SeedPostDefinition> posts = index(pack.posts(), SeedPostDefinition::seedKey);
        Set<String> accounts = keys(pack.accounts(), SeedAccountDefinition::seedKey);
        Set<String> comments = keys(pack.comments(), SeedCommentDefinition::seedKey);
        Map<String, Integer> commentsByTarget = new LinkedHashMap<>();
        for (SeedCommentDefinition comment : pack.comments()) {
            String target = validCommentTarget(comment, posts.keySet());
            if (target != null) {
                commentsByTarget.merge(target, 1, Integer::sum);
            }
            boolean validReference = hasExactlyOnePostReference(comment.postSeedKey(), comment.postSlug());
            if (!validReference) {
                errors.add("interaction post reference must use exactly one of postSeedKey or postSlug: "
                        + comment.seedKey());
            }
            SeedPostDefinition post = isBlank(comment.postSeedKey()) ? null : posts.get(comment.postSeedKey());
            if (!isBlank(comment.postSeedKey()) && post == null) {
                errors.add("missing comment post: " + comment.seedKey() + " -> " + comment.postSeedKey());
            }
            if (!accounts.contains(comment.authorSeedKey())) {
                errors.add("missing comment author: " + comment.seedKey() + " -> " + comment.authorSeedKey());
            }
            if (!isBlank(comment.parentSeedKey()) && !comments.contains(comment.parentSeedKey())) {
                errors.add("missing parent comment: " + comment.seedKey() + " -> " + comment.parentSeedKey());
            }
            requireTimestamp(comment.createdAt(), "comment " + comment.seedKey(), errors);
            if (post != null && doesNotFollow(comment.createdAt(), post.publishTime())) {
                errors.add("interaction does not follow publication: " + comment.seedKey());
            }
        }
        for (Map.Entry<String, Integer> entry : commentsByTarget.entrySet()) {
            if (entry.getValue() > MAX_COMMENTS_PER_TARGET) {
                errors.add("comment target exceeds maximum of " + MAX_COMMENTS_PER_TARGET + ": "
                        + entry.getKey() + " -> " + entry.getValue());
            }
        }
        validateCommentTopology(pack.comments(), errors);
    }

    static void requireValidCommentGraph(List<SeedCommentDefinition> comments) {
        List<String> errors = new ArrayList<>();
        validateCommentTopology(comments, errors);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Invalid comment graph: " + String.join("; ", errors));
        }
    }

    private static void validateCommentTopology(
            List<SeedCommentDefinition> comments, List<String> errors) {
        Map<String, SeedCommentDefinition> indexed = new LinkedHashMap<>();
        comments.forEach(comment -> indexed.putIfAbsent(comment.seedKey(), comment));
        for (SeedCommentDefinition comment : comments) {
            if (comment.seedKey().equals(comment.parentSeedKey())) {
                errors.add("self parent comment: " + comment.seedKey());
                continue;
            }
            SeedCommentDefinition parent = indexed.get(comment.parentSeedKey());
            if (parent == null) {
                continue;
            }
            if (!java.util.Objects.equals(postReference(comment.postSeedKey(), comment.postSlug()),
                    postReference(parent.postSeedKey(), parent.postSlug()))) {
                errors.add("comment parent post mismatch: " + comment.seedKey() + " -> " + parent.seedKey());
            }
            if (!isBlank(parent.parentSeedKey())) {
                errors.add("nested reply: " + comment.seedKey() + " -> " + parent.seedKey());
            }
            if (comment.createdAt() != null && parent.createdAt() != null
                    && comment.createdAt().isBefore(parent.createdAt())) {
                errors.add("comment precedes parent: " + comment.seedKey() + " -> " + parent.seedKey());
            }
        }
        for (SeedCommentDefinition start : comments) {
            Set<String> path = new HashSet<>();
            SeedCommentDefinition current = start;
            while (current != null && !isBlank(current.parentSeedKey())) {
                if (!path.add(current.seedKey())) {
                    errors.add("comment parent cycle: " + start.seedKey());
                    break;
                }
                current = indexed.get(current.parentSeedKey());
            }
        }
    }

    private void validateReactions(ContentPack pack, List<String> errors) {
        addDuplicates(pack.reactions(), SeedReactionDefinition::seedKey, "duplicate reaction seedKey: ", errors);
        addStructuredDuplicates(pack.reactions(),
                reaction -> new ReactionKey(
                        reaction.accountSeedKey(),
                        postReference(reaction.postSeedKey(), reaction.postSlug()),
                        reaction.type()),
                key -> key.accountSeedKey() + "|" + key.target() + "|" + key.type(),
                "duplicate reaction triple: ", errors);
        Set<String> posts = keys(pack.posts(), SeedPostDefinition::seedKey);
        Set<String> accounts = keys(pack.accounts(), SeedAccountDefinition::seedKey);
        for (SeedReactionDefinition reaction : pack.reactions()) {
            if (!REACTION_TYPES.contains(reaction.type())) {
                errors.add("invalid reaction type: " + reaction.seedKey() + " -> " + reaction.type());
            }
            if (!hasExactlyOnePostReference(reaction.postSeedKey(), reaction.postSlug())) {
                errors.add("interaction post reference must use exactly one of postSeedKey or postSlug: "
                        + reaction.seedKey());
            }
            if (!isBlank(reaction.postSeedKey()) && !posts.contains(reaction.postSeedKey())) {
                errors.add("missing reaction post: " + reaction.seedKey() + " -> " + reaction.postSeedKey());
            }
            if (!accounts.contains(reaction.accountSeedKey())) {
                errors.add("missing reaction account: " + reaction.seedKey() + " -> " + reaction.accountSeedKey());
            }
        }
    }

    private void validateFollows(ContentPack pack, List<String> errors) {
        addDuplicates(pack.follows(), SeedFollowDefinition::seedKey, "duplicate follow seedKey: ", errors);
        addStructuredDuplicates(pack.follows(),
                follow -> new FollowEdgeKey(follow.fromAccountSeedKey(), follow.toAccountSeedKey()),
                key -> key.fromAccountSeedKey() + "|" + key.toAccountSeedKey(),
                "duplicate follow edge: ", errors);
        Set<String> accounts = keys(pack.accounts(), SeedAccountDefinition::seedKey);
        for (SeedFollowDefinition follow : pack.follows()) {
            if (follow.fromAccountSeedKey() != null && follow.fromAccountSeedKey().equals(follow.toAccountSeedKey())) {
                errors.add("self-follow: " + follow.seedKey());
            }
            if (!accounts.contains(follow.fromAccountSeedKey())) {
                errors.add("missing follow source: " + follow.seedKey() + " -> " + follow.fromAccountSeedKey());
            }
            if (!accounts.contains(follow.toAccountSeedKey())) {
                errors.add("missing follow target: " + follow.seedKey() + " -> " + follow.toAccountSeedKey());
            }
            requireTimestamp(follow.createdAt(), "follow " + follow.seedKey(), errors);
        }
    }

    private void validateViews(ContentPack pack, List<String> errors) {
        addDuplicates(pack.views(), SeedViewDefinition::seedKey, "duplicate view seedKey: ", errors);
        Set<String> posts = keys(pack.posts(), SeedPostDefinition::seedKey);
        for (SeedViewDefinition view : pack.views()) {
            if (!hasExactlyOnePostReference(view.postSeedKey(), view.postSlug())) {
                errors.add("interaction post reference must use exactly one of postSeedKey or postSlug: "
                        + view.seedKey());
            }
            if (!isBlank(view.postSeedKey()) && !posts.contains(view.postSeedKey())) {
                errors.add("missing view post: " + view.seedKey() + " -> " + view.postSeedKey());
            }
            if (view.minimumCount() < 0) {
                errors.add("negative view baseline: " + view.seedKey() + " -> " + view.minimumCount());
            } else if (view.minimumCount() > Integer.MAX_VALUE) {
                errors.add("view baseline exceeds Int32: " + view.seedKey() + " -> " + view.minimumCount());
            }
        }
    }

    private void validatePath(Path root, String postKey, String relative, List<String> errors) {
        if (root == null || relative == null || relative.isBlank()) {
            errors.add("missing Markdown path: " + postKey);
            return;
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path resolved = normalizedRoot.resolve(relative).normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            errors.add("path escapes root: " + postKey + " -> " + relative);
        }
    }

    private <T> void addDuplicates(List<T> values, Function<T, String> keyExtractor, String prefix, List<String> errors) {
        addDuplicates(values, keyExtractor, Function.identity(), prefix, errors);
    }

    private <T> void addDuplicates(
            List<T> values,
            Function<T, String> keyExtractor,
            Function<String, String> normalizer,
            String prefix,
            List<String> errors) {
        Set<String> seen = new HashSet<>();
        Set<String> reported = new HashSet<>();
        for (T value : values) {
            String key = keyExtractor.apply(value);
            String normalized = normalizer.apply(key);
            if (!seen.add(normalized) && reported.add(normalized)) {
                errors.add(prefix + key);
            }
        }
    }

    private <T, K> void addStructuredDuplicates(
            List<T> values,
            Function<T, K> keyExtractor,
            Function<K, String> diagnosticFormatter,
            String prefix,
            List<String> errors) {
        Set<K> seen = new HashSet<>();
        Set<K> reported = new HashSet<>();
        for (T value : values) {
            K key = keyExtractor.apply(value);
            if (!seen.add(key) && reported.add(key)) {
                errors.add(prefix + diagnosticFormatter.apply(key));
            }
        }
    }

    private String foldDatabaseIdentifier(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private <T> Set<String> keys(List<T> values, Function<T, String> keyExtractor) {
        Set<String> result = new HashSet<>();
        values.forEach(value -> result.add(keyExtractor.apply(value)));
        return result;
    }

    private <T> Map<String, T> index(List<T> values, Function<T, String> keyExtractor) {
        Map<String, T> result = new HashMap<>();
        values.forEach(value -> result.putIfAbsent(keyExtractor.apply(value), value));
        return result;
    }

    private void requireNonblank(String value, String error, List<String> errors) {
        if (value == null || value.isBlank()) {
            errors.add(error);
        }
    }

    private void requireTimestamp(Instant timestamp, String label, List<String> errors) {
        if (timestamp == null) {
            errors.add("missing timestamp: " + label);
        }
    }

    private void validateExpectedCount(
            Integer expected, long actual, String label, boolean required, List<String> errors) {
        if (expected == null) {
            if (required) {
                errors.add("missing expected " + label + " count");
            }
            return;
        }
        if (expected.longValue() != actual) {
            errors.add("expected " + label + " count: " + expected + ", actual: " + actual);
        }
    }

    private boolean doesNotFollow(Instant interaction, Instant publication) {
        return interaction != null && publication != null && !interaction.isAfter(publication);
    }

    private static boolean hasExactlyOnePostReference(String postSeedKey, String postSlug) {
        return isBlank(postSeedKey) != isBlank(postSlug);
    }

    private static String postReference(String postSeedKey, String postSlug) {
        if (!isBlank(postSeedKey)) {
            return "seed:" + postSeedKey;
        }
        return isBlank(postSlug) ? null : "slug:" + postSlug;
    }

    private static String validCommentTarget(
            SeedCommentDefinition comment, Set<String> declaredPostSeedKeys) {
        if (!hasExactlyOnePostReference(comment.postSeedKey(), comment.postSlug())) {
            return null;
        }
        if (!isBlank(comment.postSeedKey()) && !declaredPostSeedKeys.contains(comment.postSeedKey())) {
            return null;
        }
        return postReference(comment.postSeedKey(), comment.postSlug());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record ReactionKey(String accountSeedKey, String target, String type) {
    }

    private record FollowEdgeKey(String fromAccountSeedKey, String toAccountSeedKey) {
    }

    /**
     * Non-blocking diagnostics produced alongside structural validation.
     *
     * @param warnings stable ordered warning messages
     */
    public record ValidationResult(List<String> warnings) {

        /**
         * Protects diagnostics from caller mutation.
         */
        public ValidationResult {
            warnings = List.copyOf(warnings);
        }
    }
}
