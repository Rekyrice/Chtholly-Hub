package com.chtholly.seed;

import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.function.DoubleSupplier;
import org.springframework.stereotype.Component;

/**
 * Decides whether a seed persona should join a seeded discussion.
 */
@Component
public class SeedInteractionPolicy {

    private final DoubleSupplier randomSupplier;

    public SeedInteractionPolicy() {
        this(Math::random);
    }

    SeedInteractionPolicy(DoubleSupplier randomSupplier) {
        this.randomSupplier = Objects.requireNonNull(randomSupplier, "randomSupplier");
    }

    /**
     * Combines interest relevance and probability gating.
     *
     * @param account candidate seed account
     * @param topicTags post or comment tags
     * @param contextText post/comment context
     * @param probability random probability threshold
     * @param minInterestScore minimum relevance score
     * @return true if the persona should comment
     */
    public boolean shouldComment(SeedAccountProfile account,
                                 Collection<String> topicTags,
                                 String contextText,
                                 double probability,
                                 double minInterestScore) {
        return interestScore(account, topicTags, contextText) >= minInterestScore
                && randomSupplier.getAsDouble() < probability;
    }

    /**
     * Scores simple semantic overlap between persona interests and a topic.
     *
     * <p>The seed system must work without an embedding service during local bootstrap,
     * so this deterministic scorer treats direct containment and common anime/tech
     * stems as a lightweight semantic match.
     */
    public double interestScore(SeedAccountProfile account, Collection<String> topicTags, String contextText) {
        String target = normalize(String.join(" ", topicTags) + " " + contextText);
        if (target.isBlank()) {
            return 0.0;
        }

        int matches = 0;
        for (String interest : account.tags()) {
            if (matchesInterest(normalize(interest), target)) {
                matches++;
            }
        }
        if (matches == 0) {
            return 0.0;
        }
        if (matches == 1) {
            return 0.70;
        }
        return Math.min(1.0, 0.70 + (matches - 1) * 0.15);
    }

    private boolean matchesInterest(String interest, String target) {
        if (interest.isBlank()) {
            return false;
        }
        if (target.contains(interest) || interestContainsTargetToken(interest, target)) {
            return true;
        }
        if (interest.contains("技术") || interest.contains("编程")) {
            return containsAny(target, "技术", "编程", "java", "python", "后端", "rag", "agent", "embedding");
        }
        if (interest.contains("番") || interest.contains("动漫") || interest.contains("动画")) {
            return containsAny(target, "番", "动漫", "动画", "追番", "观后感");
        }
        if (interest.contains("生活") || interest.contains("随笔") || interest.contains("治愈")) {
            return containsAny(target, "生活", "日常", "随笔", "治愈", "恢复");
        }
        if (interest.contains("书") || interest.contains("文学") || interest.contains("历史")) {
            return containsAny(target, "书", "阅读", "文学", "历史", "书评");
        }
        if (interest.contains("游戏")) {
            return containsAny(target, "游戏", "unity", "godot", "手感");
        }
        if (interest.contains("设计") || interest.contains("ui") || interest.contains("ux")) {
            return containsAny(target, "设计", "ui", "ux", "配色", "界面");
        }
        return false;
    }

    private boolean interestContainsTargetToken(String interest, String target) {
        for (String token : target.split("\\s+")) {
            if (token.length() >= 2 && interest.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(String target, String... keywords) {
        for (String keyword : keywords) {
            if (target.contains(normalize(keyword))) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\s，。！？、：；“”‘’（）《》【】]+", " ")
                .trim();
    }
}
