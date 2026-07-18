package com.chtholly.seed.contentpack;

import com.chtholly.seed.contentpack.model.SeedCommentDefinition;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ContentPackV3CommunityContractTest {

    private final ContentPackLoader loader = new ContentPackLoader();
    private final ContentPackValidator validator = new ContentPackValidator();

    @Test
    void contentV3DeclaresTheApprovedCommunityProfilesAndInteractionTotals() throws Exception {
        var pack = loader.load(Path.of("..", "..", "content", "seed", "content-v3").toAbsolutePath().normalize());

        validator.validate(pack);

        var manifest = pack.manifest();
        assertThat(manifest.expectedComments()).isEqualTo(96);
        assertThat(manifest.expectedRootComments()).isEqualTo(72);
        assertThat(manifest.expectedReplies()).isEqualTo(24);
        assertThat(manifest.expectedLikes()).isEqualTo(168);
        assertThat(manifest.expectedFavorites()).isEqualTo(80);
        assertThat(manifest.expectedFollows()).isEqualTo(28);
        assertThat(manifest.expectedViews()).isEqualTo(44);
        assertThat(manifest.expectedCommentedTargets()).isEqualTo(44);

        Set<String> accountKeys = new HashSet<>();
        pack.accounts().forEach(account -> {
            accountKeys.add(account.seedKey());
            assertThat(account.bio()).isNotBlank();
            assertThat(account.bio().codePointCount(0, account.bio().length())).isBetween(20, 60);
            assertThat(account.tags()).hasSizeBetween(2, 4);
            assertThat(account.joinedAt()).isNotNull();
            assertThat(account.joinedAt()).isBetween(
                    Instant.parse("2026-02-01T00:00:00Z"), Instant.parse("2026-06-30T23:59:59Z"));
        });
        assertThat(accountKeys).hasSize(8);

        assertThat(pack.comments()).hasSize(manifest.expectedComments());
        assertThat(pack.comments().stream().filter(comment -> isBlank(comment.parentSeedKey())))
                .hasSize(manifest.expectedRootComments());
        assertThat(pack.comments().stream().filter(comment -> !isBlank(comment.parentSeedKey())))
                .hasSize(manifest.expectedReplies());
        assertThat(pack.comments().stream()
                .map(comment -> target(comment.postSeedKey(), comment.postSlug()))
                .distinct()).hasSize(manifest.expectedCommentedTargets());
        assertThat(pack.comments()).allSatisfy(comment -> {
            assertThat(comment.authorSeedKey()).isIn(accountKeys);
            assertThat(hasExactlyOneTarget(comment.postSeedKey(), comment.postSlug())).isTrue();
            assertThat(comment.content().codePointCount(0, comment.content().length())).isBetween(18, 90);
        });
        List<String> forbiddenTemplatePhrases = List.of(
                "总结得很好", "很有启发", "核心观点是", "值得我们思考", "首先，", "其次，", "最后，");
        assertThat(pack.comments()).allSatisfy(comment ->
                assertThat(forbiddenTemplatePhrases.stream().noneMatch(comment.content()::contains)).isTrue());
        Map<String, Long> commentsByAccount = pack.comments().stream()
                .collect(Collectors.groupingBy(SeedCommentDefinition::authorSeedKey, Collectors.counting()));
        assertThat(commentsByAccount).containsExactlyInAnyOrderEntriesOf(Map.of(
                "night-coder", 11L,
                "algo-runner", 14L,
                "anime-critic", 13L,
                "design-sis", 12L,
                "moyu-master", 14L,
                "indie-dev", 12L,
                "book-notes", 10L,
                "photo-walker", 10L));
        Instant earliestComment = pack.comments().stream()
                .map(SeedCommentDefinition::createdAt)
                .min(Instant::compareTo)
                .orElseThrow();
        Instant latestComment = pack.comments().stream()
                .map(SeedCommentDefinition::createdAt)
                .max(Instant::compareTo)
                .orElseThrow();
        assertThat(earliestComment).isBeforeOrEqualTo(Instant.parse("2026-03-07T13:14:00Z"));
        assertThat(latestComment).isAfterOrEqualTo(Instant.parse("2026-07-18T01:02:00Z"));
        assertThat(Duration.between(earliestComment, latestComment).toDays()).isGreaterThanOrEqualTo(120L);

        assertThat(pack.reactions()).hasSize(manifest.expectedLikes() + manifest.expectedFavorites());
        assertThat(pack.reactions().stream().filter(reaction -> "like".equals(reaction.type())))
                .hasSize(manifest.expectedLikes());
        assertThat(pack.reactions().stream().filter(reaction -> "fav".equals(reaction.type())))
                .hasSize(manifest.expectedFavorites());
        assertThat(pack.reactions()).allSatisfy(reaction -> {
            assertThat(reaction.accountSeedKey()).isIn(accountKeys);
            assertThat(hasExactlyOneTarget(reaction.postSeedKey(), reaction.postSlug())).isTrue();
        });
        assertThat(pack.reactions().stream()
                .map(reaction -> new ReactionTuple(
                        reaction.accountSeedKey(),
                        target(reaction.postSeedKey(), reaction.postSlug()),
                        reaction.type()))
                .distinct()).hasSize(manifest.expectedLikes() + manifest.expectedFavorites());
        assertThat(pack.reactions().stream()
                .map(reaction -> target(reaction.postSeedKey(), reaction.postSlug()))
                .distinct()).hasSize(44);

        assertThat(pack.follows()).hasSize(manifest.expectedFollows());
        assertThat(pack.follows()).allSatisfy(follow -> {
            assertThat(follow.fromAccountSeedKey()).isIn(accountKeys);
            assertThat(hasExactlyOneTarget(follow.toAccountSeedKey(), follow.toHandle())).isTrue();
            if (follow.toAccountSeedKey() != null) {
                assertThat(follow.toAccountSeedKey()).isIn(accountKeys);
            } else {
                assertThat(follow.toHandle()).isEqualToIgnoringCase("Rekyrice");
            }
        });
        assertThat(pack.follows().stream()
                .map(follow -> new FollowEdge(
                        follow.fromAccountSeedKey(),
                        follow.toAccountSeedKey() == null
                                ? "handle:" + follow.toHandle().toLowerCase(java.util.Locale.ROOT)
                                : "seed:" + follow.toAccountSeedKey()))
                .distinct()).hasSize(manifest.expectedFollows());
        assertThat(pack.follows().stream().filter(follow -> follow.toHandle() != null)).hasSize(6);

        assertThat(pack.views()).hasSize(manifest.expectedViews());
        assertThat(pack.views()).allSatisfy(view -> {
            assertThat(view.minimumCount()).isBetween(48L, 420L);
            assertThat(hasExactlyOneTarget(view.postSeedKey(), view.postSlug())).isTrue();
        });
        assertThat(pack.views().stream().map(view -> target(view.postSeedKey(), view.postSlug())).distinct())
                .hasSize(manifest.expectedViews());
        assertThat(pack.views().stream().filter(view -> view.postSlug() != null).map(view -> view.postSlug()))
                .containsExactlyInAnyOrder("111", "welcome-chtholly-hub", "2026-winter-anime-list", "why-chtholly");

        assertThat(pack.comments().stream().map(SeedCommentDefinition::authorSeedKey).collect(java.util.stream.Collectors.toSet()))
                .containsExactlyInAnyOrderElementsOf(accountKeys);
    }

    private boolean hasExactlyOneTarget(String postSeedKey, String postSlug) {
        return (postSeedKey == null || postSeedKey.isBlank()) != (postSlug == null || postSlug.isBlank());
    }

    private String target(String postSeedKey, String postSlug) {
        return postSeedKey == null ? "slug:" + postSlug : "seed:" + postSeedKey;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record ReactionTuple(String accountSeedKey, String target, String type) {
    }

    private record FollowEdge(String fromAccountSeedKey, String toAccountSeedKey) {
    }
}
