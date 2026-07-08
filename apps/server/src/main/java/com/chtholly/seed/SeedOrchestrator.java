package com.chtholly.seed;

import com.chtholly.post.id.SnowflakeIdGenerator;
import com.chtholly.search.index.SearchIndexService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Coordinates cold-start recommendations, seed accounts, content, and interactions.
 */
@Slf4j
@Service
public class SeedOrchestrator {

    private static final double MIN_RECOMMENDATION_SCORE = 7.5;
    private static final int BANGUMI_LIMIT = 20;

    private final SeedMapper mapper;
    private final BangumiRecommendationSource bangumiSource;
    private final SeedTextGenerator textGenerator;
    private final SnowflakeIdGenerator idGenerator;
    private final ObjectMapper objectMapper;
    private final SearchIndexService searchIndexService;
    private final SeedInteractionService interactionService;
    private final Clock clock;
    private final SeedAccountGenerator accountGenerator = new SeedAccountGenerator();
    private final SeedContentGenerator contentGenerator = new SeedContentGenerator();

    @Autowired
    public SeedOrchestrator(SeedMapper mapper,
                            BangumiRecommendationSource bangumiSource,
                            SeedTextGenerator textGenerator,
                            SnowflakeIdGenerator idGenerator,
                            ObjectMapper objectMapper,
                            ObjectProvider<SearchIndexService> searchIndexServiceProvider,
                            ObjectProvider<SeedInteractionService> interactionServiceProvider) {
        this(mapper,
                bangumiSource,
                textGenerator,
                idGenerator,
                objectMapper,
                searchIndexServiceProvider.getIfAvailable(),
                interactionServiceProvider.getIfAvailable(),
                Clock.systemUTC());
    }

    SeedOrchestrator(SeedMapper mapper,
                     BangumiRecommendationSource bangumiSource,
                     SeedTextGenerator textGenerator,
                     SnowflakeIdGenerator idGenerator,
                     ObjectMapper objectMapper,
                     SearchIndexService searchIndexService,
                     Clock clock) {
        this(mapper, bangumiSource, textGenerator, idGenerator, objectMapper, searchIndexService, null, clock);
    }

    SeedOrchestrator(SeedMapper mapper,
                     BangumiRecommendationSource bangumiSource,
                     SeedTextGenerator textGenerator,
                     SnowflakeIdGenerator idGenerator,
                     ObjectMapper objectMapper,
                     SearchIndexService searchIndexService,
                     SeedInteractionService interactionService,
                     Clock clock) {
        this.mapper = mapper;
        this.bangumiSource = bangumiSource;
        this.textGenerator = textGenerator;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
        this.searchIndexService = searchIndexService;
        this.interactionService = interactionService;
        this.clock = clock;
    }

    /**
     * Runs the selected seed mode.
     *
     * @param options mode and dry-run flag
     * @return seed summary
     */
    @Transactional
    public SeedRunSummary run(SeedRunOptions options) {
        String marker = options.mode().markerKey();
        if (!options.dryRun() && mapper.existsSeed(marker)) {
            return SeedRunSummary.skipped(options.mode(), false);
        }

        SeedPlan plan = buildPlan(options.mode());
        SeedRunSummary summary = plan.summary(options.mode(), options.dryRun(), false);
        if (options.dryRun()) {
            log.info("Seed dry-run summary: {}", summary);
            return summary;
        }

        persist(plan);
        indexSeedPosts(plan.posts());
        mapper.markSeed(marker, toJson(summary));
        return summary;
    }

    private SeedPlan buildPlan(SeedRunMode mode) {
        List<BangumiRecommendationSeed> recommendations = mode == SeedRunMode.ACCOUNTS
                || mode == SeedRunMode.CONTENT_ONLY
                ? List.of()
                : buildRecommendations();
        AccountPlan accounts = mode == SeedRunMode.BANGUMI
                ? AccountPlan.empty()
                : buildAccountPlan(mode);
        return new SeedPlan(
                recommendations,
                accounts.users(),
                accounts.posts(),
                accounts.comments(),
                accounts.follows(),
                accounts.interactionAccounts(),
                accounts.interactionPosts());
    }

    private List<BangumiRecommendationSeed> buildRecommendations() {
        Instant now = Instant.now(clock);
        return bangumiSource.fetchTopAnime(BANGUMI_LIMIT).stream()
                .filter(subject -> subject.score() > MIN_RECOMMENDATION_SCORE)
                .limit(BANGUMI_LIMIT)
                .map(subject -> new BangumiRecommendationSeed(
                        subject.bangumiId(),
                        subject.title(),
                        subject.titleCn(),
                        subject.coverUrl(),
                        subject.score(),
                        textGenerator.bangumiReview(subject),
                        toJson(subject.tags()),
                        now))
                .toList();
    }

    private AccountPlan buildAccountPlan(SeedRunMode mode) {
        boolean contentOnly = mode == SeedRunMode.CONTENT_ONLY;
        Instant now = Instant.now(clock);
        List<SeedAccountProfile> profiles = accountGenerator.accounts();
        List<SeedUserRow> users = new ArrayList<>();
        List<SeedPostWithPlan> posts = new ArrayList<>();
        List<SeedInteractionAccount> interactionAccounts = new ArrayList<>();

        for (SeedAccountProfile profile : profiles) {
            Long existingUserId = mapper.findUserIdByHandle(profile.handle());
            long userId = existingUserId != null ? existingUserId : idGenerator.nextId();
            users.add(toUserRow(userId, profile, now.minus(14, ChronoUnit.DAYS), now));
            if (!contentOnly) {
                interactionAccounts.add(new SeedInteractionAccount(userId, profile));
            }
            for (SeedPostPlan postPlan : contentGenerator.postsFor(profile)) {
                Instant publishedAt = now.minus(postPlan.daysAgo(), ChronoUnit.DAYS)
                        .plus(postPlan.slot() * 3L, ChronoUnit.HOURS);
                posts.add(new SeedPostWithPlan(
                        profile,
                        postPlan,
                        toPostRow(userId, profile, postPlan, publishedAt)));
            }
        }

        List<SeedCommentRow> comments = contentOnly
                ? List.of()
                : buildComments(profiles, users, posts);
        List<SeedFollowRow> follows = contentOnly
                ? List.of()
                : buildFollows(users, now.minus(13, ChronoUnit.DAYS));
        List<SeedPostInteraction> interactionPosts = contentOnly
                ? List.of()
                : posts.stream()
                        .map(post -> new SeedPostInteraction(
                                post.row().id(),
                                post.row().creatorId(),
                                post.plan().title(),
                                post.plan().tags(),
                                post.row().description()))
                        .toList();
        return new AccountPlan(
                users,
                posts.stream().map(SeedPostWithPlan::row).toList(),
                comments,
                follows,
                interactionAccounts,
                interactionPosts);
    }

    private SeedUserRow toUserRow(long userId, SeedAccountProfile profile, Instant createdAt, Instant updatedAt) {
        return new SeedUserRow(
                userId,
                profile.handle() + "@seed.chtholly.invalid",
                profile.nickname(),
                profile.avatar(),
                profile.bio(),
                profile.handle(),
                profile.gender(),
                profile.birthday(),
                profile.school(),
                toJson(profile.tags()),
                createdAt,
                updatedAt);
    }

    private SeedPostRow toPostRow(long userId,
                                  SeedAccountProfile profile,
                                  SeedPostPlan postPlan,
                                  Instant publishedAt) {
        String body = textGenerator.postBody(profile, postPlan);
        String slug = "seed-" + profile.handle() + "-" + postPlan.slot();
        String objectKey = "seed/posts/" + slug + ".md";
        return new SeedPostRow(
                idGenerator.nextId(),
                userId,
                postPlan.title(),
                slug,
                abbreviate(body.replaceAll("\\s+", " "), 50),
                "seed://" + objectKey,
                objectKey,
                body.getBytes(StandardCharsets.UTF_8).length,
                sha256(body),
                toJson(postPlan.tags()),
                toJson(List.of()),
                publishedAt.minus(2, ChronoUnit.HOURS),
                publishedAt,
                publishedAt);
    }

    private List<SeedCommentRow> buildComments(
            List<SeedAccountProfile> profiles,
            List<SeedUserRow> users,
            List<SeedPostWithPlan> posts) {
        List<SeedCommentRow> comments = new ArrayList<>();
        for (int i = 0; i < users.size(); i++) {
            SeedUserRow commenter = users.get(i);
            SeedAccountProfile commenterProfile = profiles.get(i);
            for (int j = 1; j <= 2; j++) {
                SeedPostWithPlan target = posts.get(((i + j) * 3) % posts.size());
                SeedAccountProfile authorProfile = target.profile();
                Instant createdAt = target.row().publishTime().plus(6L + j * 5L, ChronoUnit.HOURS);
                comments.add(new SeedCommentRow(
                        idGenerator.nextId(),
                        target.row().id(),
                        commenter.id(),
                        textGenerator.comment(commenterProfile, authorProfile, target.plan()),
                        createdAt,
                        createdAt));
            }
        }
        return comments;
    }

    private List<SeedFollowRow> buildFollows(List<SeedUserRow> users, Instant baseTime) {
        List<SeedFollowRow> follows = new ArrayList<>();
        for (int i = 0; i < users.size(); i++) {
            for (int step = 1; step <= 3; step++) {
                SeedUserRow from = users.get(i);
                SeedUserRow to = users.get((i + step) % users.size());
                Instant createdAt = baseTime.plus(i * 6L + step, ChronoUnit.HOURS);
                follows.add(new SeedFollowRow(idGenerator.nextId(), from.id(), to.id(), createdAt, createdAt));
            }
        }
        return follows;
    }

    private void persist(SeedPlan plan) {
        plan.recommendations().forEach(mapper::insertBangumiRecommendation);
        plan.users().forEach(mapper::insertSeedUser);
        plan.posts().forEach(mapper::insertSeedPost);
        scheduleSeedInteractions(plan);
        plan.comments().forEach(mapper::insertSeedComment);
        for (SeedFollowRow follow : plan.follows()) {
            mapper.upsertFollowing(follow);
            mapper.upsertFollower(follow);
        }
    }

    private void scheduleSeedInteractions(SeedPlan plan) {
        if (interactionService == null || plan.interactionAccounts().isEmpty()) {
            return;
        }
        for (SeedPostInteraction post : plan.interactionPosts()) {
            interactionService.scheduleMultiRoundInteraction(post, plan.interactionAccounts());
        }
    }

    /** 种子文章写入 MySQL 后立即 upsert 到 ES，避免 Hub Feed 仍只显示旧索引。 */
    private void indexSeedPosts(List<SeedPostRow> posts) {
        if (searchIndexService == null || posts.isEmpty()) {
            return;
        }
        for (SeedPostRow post : posts) {
            Long indexedId = mapper.findPostIdBySlug(post.slug());
            searchIndexService.upsertPost(indexedId != null ? indexedId : post.id());
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize seed data", e);
        }
    }

    private static String sha256(String body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    private record SeedPostWithPlan(SeedAccountProfile profile, SeedPostPlan plan, SeedPostRow row) {
    }

    private record AccountPlan(
            List<SeedUserRow> users,
            List<SeedPostRow> posts,
            List<SeedCommentRow> comments,
            List<SeedFollowRow> follows,
            List<SeedInteractionAccount> interactionAccounts,
            List<SeedPostInteraction> interactionPosts) {
        static AccountPlan empty() {
            return new AccountPlan(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }

    private record SeedPlan(
            List<BangumiRecommendationSeed> recommendations,
            List<SeedUserRow> users,
            List<SeedPostRow> posts,
            List<SeedCommentRow> comments,
            List<SeedFollowRow> follows,
            List<SeedInteractionAccount> interactionAccounts,
            List<SeedPostInteraction> interactionPosts) {
        SeedRunSummary summary(SeedRunMode mode, boolean dryRun, boolean skipped) {
            return new SeedRunSummary(
                    mode,
                    dryRun,
                    skipped,
                    recommendations.size(),
                    users.size(),
                    posts.size(),
                    comments.size(),
                    follows.size());
        }
    }
}
