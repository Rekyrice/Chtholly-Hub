package com.chtholly.seed.contentpack;

import com.chtholly.seed.contentpack.model.ContentPack;
import com.chtholly.seed.contentpack.model.ContentPackManifest;
import com.chtholly.seed.contentpack.model.SeedAccountDefinition;
import com.chtholly.seed.contentpack.model.SeedCommentDefinition;
import com.chtholly.seed.contentpack.model.SeedFollowDefinition;
import com.chtholly.seed.contentpack.model.SeedPostDefinition;
import com.chtholly.seed.contentpack.model.SeedReactionDefinition;
import com.chtholly.seed.contentpack.model.SeedViewDefinition;

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

/**
 * Validates a loaded content pack before any persistence boundary is entered.
 */
public final class ContentPackValidator {

    private static final Pattern HANDLE_PATTERN = Pattern.compile("[A-Za-z0-9_]{3,64}");
    private static final Set<String> STAGES = Set.of("review", "complete");
    private static final Set<String> REACTION_TYPES = Set.of("like", "fav");

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
        validatePosts(pack, errors);
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
            if (account.handle() == null || !HANDLE_PATTERN.matcher(account.handle()).matches()) {
                errors.add("invalid account handle: " + account.seedKey());
            }
            if (!pack.assets().containsKey(account.avatarAsset())) {
                errors.add("missing avatar asset: " + account.seedKey() + " -> " + account.avatarAsset());
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
            if (!accountKeys.contains(post.authorSeedKey())) {
                errors.add("missing post author: " + post.seedKey() + " -> " + post.authorSeedKey());
            }
            if (!pack.assets().containsKey(post.coverAsset())) {
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
        }
    }

    private void validateComments(ContentPack pack, List<String> errors) {
        addDuplicates(pack.comments(), SeedCommentDefinition::seedKey, "duplicate comment seedKey: ", errors);
        Map<String, SeedPostDefinition> posts = index(pack.posts(), SeedPostDefinition::seedKey);
        Set<String> accounts = keys(pack.accounts(), SeedAccountDefinition::seedKey);
        Set<String> comments = keys(pack.comments(), SeedCommentDefinition::seedKey);
        for (SeedCommentDefinition comment : pack.comments()) {
            SeedPostDefinition post = posts.get(comment.postSeedKey());
            if (post == null) {
                errors.add("missing comment post: " + comment.seedKey() + " -> " + comment.postSeedKey());
            }
            if (!accounts.contains(comment.authorSeedKey())) {
                errors.add("missing comment author: " + comment.seedKey() + " -> " + comment.authorSeedKey());
            }
            if (comment.parentSeedKey() != null && !comments.contains(comment.parentSeedKey())) {
                errors.add("missing parent comment: " + comment.seedKey() + " -> " + comment.parentSeedKey());
            }
            requireTimestamp(comment.createdAt(), "comment " + comment.seedKey(), errors);
            if (post != null && doesNotFollow(comment.createdAt(), post.publishTime())) {
                errors.add("interaction does not follow publication: " + comment.seedKey());
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
            if (!java.util.Objects.equals(comment.postSeedKey(), parent.postSeedKey())) {
                errors.add("comment parent post mismatch: " + comment.seedKey() + " -> " + parent.seedKey());
            }
            if (comment.createdAt() != null && parent.createdAt() != null
                    && comment.createdAt().isBefore(parent.createdAt())) {
                errors.add("comment precedes parent: " + comment.seedKey() + " -> " + parent.seedKey());
            }
        }
        for (SeedCommentDefinition start : comments) {
            Set<String> path = new HashSet<>();
            SeedCommentDefinition current = start;
            while (current != null && current.parentSeedKey() != null) {
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
        Set<String> posts = keys(pack.posts(), SeedPostDefinition::seedKey);
        Set<String> accounts = keys(pack.accounts(), SeedAccountDefinition::seedKey);
        for (SeedReactionDefinition reaction : pack.reactions()) {
            if (!REACTION_TYPES.contains(reaction.type())) {
                errors.add("invalid reaction type: " + reaction.seedKey() + " -> " + reaction.type());
            }
            if (!posts.contains(reaction.postSeedKey())) {
                errors.add("missing reaction post: " + reaction.seedKey() + " -> " + reaction.postSeedKey());
            }
            if (!accounts.contains(reaction.accountSeedKey())) {
                errors.add("missing reaction account: " + reaction.seedKey() + " -> " + reaction.accountSeedKey());
            }
        }
    }

    private void validateFollows(ContentPack pack, List<String> errors) {
        addDuplicates(pack.follows(), SeedFollowDefinition::seedKey, "duplicate follow seedKey: ", errors);
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
            if (!posts.contains(view.postSeedKey())) {
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

    private boolean doesNotFollow(Instant interaction, Instant publication) {
        return interaction != null && publication != null && !interaction.isAfter(publication);
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
