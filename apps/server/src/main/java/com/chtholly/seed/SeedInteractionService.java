package com.chtholly.seed;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Coordinates delayed multi-round interactions between seed accounts.
 */
@Slf4j
@Service
public class SeedInteractionService {

    private static final int MAX_ROUNDS_PER_POST = 3;
    private static final int MAX_COMMENTS_PER_POST = 3;
    private static final int MAX_DAILY_COMMENTS_PER_ACCOUNT = 6;
    private static final int MAX_QUALITY_ATTEMPTS = 2;
    private static final double MIN_QUALITY_SCORE = 3.5;
    private static final int DUE_TASK_BATCH_SIZE = 20;

    private final SeedInteractionStateStore stateStore;
    private final SeedInteractionCommentWriter commentWriter;
    private final SeedInteractionPolicy policy;
    private final SeedInteractionTextGenerator textGenerator;
    private final SeedInteractionQualityEvaluator qualityEvaluator;
    private final Clock clock;
    private final SeedInteractionDelayPlanner delayPlanner;

    @Autowired
    public SeedInteractionService(SeedInteractionStateStore stateStore,
                                  SeedInteractionCommentWriter commentWriter,
                                  SeedInteractionPolicy policy,
                                  SeedInteractionTextGenerator textGenerator,
                                  SeedInteractionQualityEvaluator qualityEvaluator) {
        this(stateStore,
                commentWriter,
                policy,
                textGenerator,
                qualityEvaluator,
                Clock.systemUTC(),
                SeedInteractionDelayPlanner.random());
    }

    SeedInteractionService(SeedInteractionStateStore stateStore,
                           SeedInteractionCommentWriter commentWriter,
                           SeedInteractionPolicy policy,
                           SeedInteractionTextGenerator textGenerator,
                           SeedInteractionQualityEvaluator qualityEvaluator,
                           Clock clock,
                           SeedInteractionDelayPlanner delayPlanner) {
        this.stateStore = stateStore;
        this.commentWriter = commentWriter;
        this.policy = policy;
        this.textGenerator = textGenerator;
        this.qualityEvaluator = qualityEvaluator;
        this.clock = clock;
        this.delayPlanner = delayPlanner;
    }

    /**
     * Schedules the first discussion round after a seed post is created.
     *
     * @param post seed post context
     * @param accounts all seed accounts
     */
    public void scheduleMultiRoundInteraction(SeedPostInteraction post, List<SeedInteractionAccount> accounts) {
        if (stateStore.currentRounds(post.postId()) >= MAX_ROUNDS_PER_POST
                || stateStore.commentCount(post.postId()) >= MAX_COMMENTS_PER_POST) {
            return;
        }
        List<SeedInteractionAccount> candidates = accounts.stream()
                .filter(account -> account.userId() != post.authorUserId())
                .toList();
        if (candidates.isEmpty()) {
            return;
        }
        stateStore.enqueue(newTask(
                post,
                1,
                null,
                null,
                candidates,
                Instant.now(clock).plus(delayPlanner.delayForRound(1))));
    }

    /**
     * Polls due Redis tasks and executes ready seed comments.
     */
    @Scheduled(fixedRate = 60_000)
    public void processDueInteractions() {
        processDueInteractions(Instant.now(clock), DUE_TASK_BATCH_SIZE);
    }

    void processDueInteractions(Instant now, int limit) {
        for (SeedInteractionTask task : stateStore.dueTasks(now, limit)) {
            try {
                processTask(task, now);
            } catch (Exception e) {
                log.warn("Seed interaction task failed and will be dropped: {}", task.id(), e);
            } finally {
                stateStore.removeTask(task.id());
            }
        }
    }

    private void processTask(SeedInteractionTask task, Instant now) {
        if (stateStore.currentRounds(task.postId()) >= MAX_ROUNDS_PER_POST
                || stateStore.commentCount(task.postId()) >= MAX_COMMENTS_PER_POST) {
            return;
        }

        SeedInteractionContext context = new SeedInteractionContext(
                task.postId(),
                task.postTitle(),
                task.postTags(),
                task.postExcerpt(),
                task.parentCommentId(),
                task.parentCommentContent(),
                task.round());
        SeedInteractionAccount commenter = selectCommenter(task, context, now);
        if (commenter == null) {
            return;
        }

        String comment = generateAcceptedComment(commenter, context);
        if (comment == null) {
            return;
        }

        long commentId = commentWriter.writeComment(
                task.postId(),
                task.parentCommentId(),
                commenter.userId(),
                comment,
                now);
        stateStore.rememberComment(task.postId(), commenter.userId(), LocalDate.ofInstant(now, ZoneOffset.UTC));
        scheduleNextRound(task, commenter.userId(), commentId, comment, now);
    }

    private SeedInteractionAccount selectCommenter(
            SeedInteractionTask task,
            SeedInteractionContext context,
            Instant now) {
        double probability = task.round() == 1 ? 0.30 : 0.20;
        double minInterestScore = task.round() == 1 ? 0.60 : 0.50;
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        return task.candidates().stream()
                .filter(account -> account.userId() != task.authorUserId())
                .filter(account -> !stateStore.hasCommented(task.postId(), account.userId()))
                .filter(account -> stateStore.dailyComments(account.userId(), today) < MAX_DAILY_COMMENTS_PER_ACCOUNT)
                .sorted(Comparator.comparingDouble((SeedInteractionAccount account) ->
                        policy.interestScore(account.profile(), context.postTags(), contextText(context))).reversed())
                .filter(account -> policy.shouldComment(
                        account.profile(),
                        context.postTags(),
                        contextText(context),
                        probability,
                        minInterestScore))
                .findFirst()
                .orElse(null);
    }

    private String generateAcceptedComment(SeedInteractionAccount account, SeedInteractionContext context) {
        for (int attempt = 0; attempt < MAX_QUALITY_ATTEMPTS; attempt++) {
            String comment = textGenerator.generateReply(account, context);
            if (comment == null || comment.isBlank()) {
                continue;
            }
            double score = qualityEvaluator.evaluate(account, context, comment);
            if (score >= MIN_QUALITY_SCORE) {
                return comment.trim();
            }
        }
        return null;
    }

    private void scheduleNextRound(SeedInteractionTask task,
                                   long commenterUserId,
                                   long commentId,
                                   String comment,
                                   Instant now) {
        int nextRound = task.round() + 1;
        if (nextRound > MAX_ROUNDS_PER_POST
                || stateStore.currentRounds(task.postId()) >= MAX_ROUNDS_PER_POST
                || stateStore.commentCount(task.postId()) >= MAX_COMMENTS_PER_POST) {
            return;
        }

        List<SeedInteractionAccount> remaining = new ArrayList<>(task.candidates().stream()
                .filter(account -> account.userId() != commenterUserId)
                .filter(account -> !stateStore.hasCommented(task.postId(), account.userId()))
                .toList());
        if (remaining.isEmpty()) {
            return;
        }

        SeedPostInteraction post = new SeedPostInteraction(
                task.postId(),
                task.authorUserId(),
                task.postTitle(),
                task.postTags(),
                task.postExcerpt());
        stateStore.enqueue(newTask(
                post,
                nextRound,
                commentId,
                comment,
                remaining,
                now.plus(delayPlanner.delayForRound(nextRound))));
    }

    private SeedInteractionTask newTask(SeedPostInteraction post,
                                        int round,
                                        Long parentCommentId,
                                        String parentCommentContent,
                                        List<SeedInteractionAccount> candidates,
                                        Instant scheduledAt) {
        return new SeedInteractionTask(
                UUID.randomUUID().toString(),
                post.postId(),
                post.authorUserId(),
                post.postTitle(),
                post.tags(),
                post.excerpt(),
                round,
                parentCommentId,
                parentCommentContent,
                candidates,
                scheduledAt);
    }

    private String contextText(SeedInteractionContext context) {
        return String.join("\n",
                context.postTitle(),
                context.postExcerpt(),
                context.parentCommentContent() == null ? "" : context.parentCommentContent());
    }
}
