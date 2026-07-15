package com.chtholly.seed.contentpack;

import com.chtholly.seed.contentpack.model.SeedCommentDefinition;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ContentPackV3CommunityContractTest {

    private final ContentPackLoader loader = new ContentPackLoader();
    private final ContentPackValidator validator = new ContentPackValidator();

    @Test
    void contentV3DeclaresTheApprovedCommunityProfilesAndInteractionTotals() throws Exception {
        var pack = loader.load(Path.of("..", "..", "content", "seed", "content-v3").toAbsolutePath().normalize());

        validator.validate(pack);

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

        assertThat(pack.comments()).hasSize(36);
        assertThat(pack.comments().stream().filter(comment -> comment.parentSeedKey() == null)).hasSize(28);
        assertThat(pack.comments().stream().filter(comment -> comment.parentSeedKey() != null)).hasSize(8);
        assertThat(pack.comments()).allSatisfy(comment -> {
            assertThat(comment.authorSeedKey()).isIn(accountKeys);
            assertThat(hasExactlyOneTarget(comment.postSeedKey(), comment.postSlug())).isTrue();
            assertThat(comment.content().codePointCount(0, comment.content().length())).isBetween(18, 90);
        });

        assertThat(pack.reactions()).hasSize(88);
        assertThat(pack.reactions().stream().filter(reaction -> "like".equals(reaction.type()))).hasSize(62);
        assertThat(pack.reactions().stream().filter(reaction -> "fav".equals(reaction.type()))).hasSize(26);
        assertThat(pack.reactions()).allSatisfy(reaction -> {
            assertThat(reaction.accountSeedKey()).isIn(accountKeys);
            assertThat(hasExactlyOneTarget(reaction.postSeedKey(), reaction.postSlug())).isTrue();
        });
        assertThat(pack.reactions().stream()
                .map(reaction -> target(reaction.postSeedKey(), reaction.postSlug()) + "|"
                        + reaction.accountSeedKey() + "|" + reaction.type())
                .distinct()).hasSize(88);

        assertThat(pack.follows()).hasSize(14);
        assertThat(pack.follows()).allSatisfy(follow -> {
            assertThat(follow.fromAccountSeedKey()).isIn(accountKeys);
            assertThat(follow.toAccountSeedKey()).isIn(accountKeys);
        });

        assertThat(pack.views()).hasSize(44);
        assertThat(pack.views()).allSatisfy(view -> {
            assertThat(view.minimumCount()).isBetween(18L, 180L);
            assertThat(hasExactlyOneTarget(view.postSeedKey(), view.postSlug())).isTrue();
        });
        assertThat(pack.views().stream().map(view -> target(view.postSeedKey(), view.postSlug())).distinct())
                .hasSize(44);
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
}
