package com.chtholly.seed;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SeedContentGeneratorTest {

    private final SeedContentGenerator generator = new SeedContentGenerator();

    @Test
    void knownPersonasHaveFourToFiveConcreteBlogPlans() {
        for (SeedAccountProfile account : new SeedAccountGenerator().accounts()) {
            List<SeedPostPlan> plans = generator.postsFor(account);

            assertThat(plans)
                    .as(account.handle())
                    .hasSizeBetween(4, 5);
            assertThat(plans)
                    .as(account.handle() + " topics should be concrete")
                    .allSatisfy(plan -> {
                        assertThat(plan.title()).hasSizeGreaterThan(10);
                        assertThat(plan.tags()).isNotEmpty();
                    });
        }
    }

    @Test
    void unknownPersonaUsesFourFallbackBlogPlans() {
        SeedAccountProfile account = new SeedAccountProfile(
                "sakura",
                "Sakura",
                "安静地记录生活和读书。",
                "/avatar.png",
                "SECRET",
                LocalDate.of(2000, 1, 1),
                "旧书架",
                List.of("治愈系", "生活感悟", "读书笔记"),
                "温柔、细腻，喜欢生活感悟和读书笔记");

        assertThat(generator.postsFor(account)).hasSize(4);
    }
}
