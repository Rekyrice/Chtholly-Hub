package com.chtholly.seed;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SeedInteractionServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-08T08:00:00Z");

    @Test
    void given_seedPost_when_scheduleMultiRoundInteraction_then_enqueuesFirstRoundWithinHumanDelay() {
        InMemoryStateStore stateStore = new InMemoryStateStore();
        SeedInteractionService service = service(stateStore, List.of(4.5));
        SeedPostInteraction post = post();

        service.scheduleMultiRoundInteraction(post, accounts());

        assertThat(stateStore.tasks).hasSize(1);
        SeedInteractionTask task = stateStore.tasks.getFirst();
        assertThat(task.round()).isEqualTo(1);
        assertThat(task.postId()).isEqualTo(post.postId());
        assertThat(task.scheduledAt()).isBetween(NOW.plus(Duration.ofMinutes(5)), NOW.plus(Duration.ofMinutes(30)));
        assertThat(task.candidates()).extracting(SeedInteractionAccount::userId)
                .doesNotContain(post.authorUserId());
    }

    @Test
    void given_dueTask_when_commentPassesQuality_then_writesCommentAndSchedulesNextRoundReply() {
        InMemoryStateStore stateStore = new InMemoryStateStore();
        RecordingCommentWriter writer = new RecordingCommentWriter();
        SeedInteractionService service = service(stateStore, writer, List.of(4.2));
        SeedPostInteraction post = post();
        service.scheduleMultiRoundInteraction(post, accounts());
        SeedInteractionTask firstRound = stateStore.tasks.getFirst();

        service.processDueInteractions(NOW.plus(Duration.ofMinutes(31)), 10);

        assertThat(writer.comments).hasSize(1);
        WrittenComment rootComment = writer.comments.getFirst();
        assertThat(rootComment.parentId()).isNull();
        assertThat(stateStore.currentRounds(post.postId())).isEqualTo(1);
        assertThat(stateStore.commentCount(post.postId())).isEqualTo(1);
        assertThat(stateStore.hasCommented(post.postId(), rootComment.userId())).isTrue();
        assertThat(stateStore.tasks).noneMatch(task -> task.id().equals(firstRound.id()));
        assertThat(stateStore.tasks).anySatisfy(task -> {
            assertThat(task.round()).isEqualTo(2);
            assertThat(task.parentCommentId()).isEqualTo(rootComment.id());
            assertThat(task.parentCommentContent()).isEqualTo(rootComment.content());
            assertThat(task.scheduledAt()).isBetween(
                    NOW.plus(Duration.ofMinutes(31 + 10)),
                    NOW.plus(Duration.ofMinutes(31 + 60)));
        });
    }

    @Test
    void given_lowQualityComment_when_processDueInteractions_then_retriesAndDropsAfterTwoFailures() {
        InMemoryStateStore stateStore = new InMemoryStateStore();
        RecordingCommentWriter writer = new RecordingCommentWriter();
        SeedInteractionService service = service(stateStore, writer, List.of(2.0, 3.0));
        service.scheduleMultiRoundInteraction(post(), accounts());

        service.processDueInteractions(NOW.plus(Duration.ofMinutes(31)), 10);

        assertThat(writer.comments).isEmpty();
        assertThat(stateStore.tasks).isEmpty();
    }

    @Test
    void given_roundLimitReached_when_processDueInteractions_then_doesNotWriteMoreComments() {
        InMemoryStateStore stateStore = new InMemoryStateStore();
        stateStore.rounds.put(1L, 3);
        stateStore.commentCounts.put(1L, 3);
        RecordingCommentWriter writer = new RecordingCommentWriter();
        SeedInteractionService service = service(stateStore, writer, List.of(4.5));
        service.scheduleMultiRoundInteraction(post(), accounts());

        service.processDueInteractions(NOW.plus(Duration.ofMinutes(31)), 10);

        assertThat(writer.comments).isEmpty();
        assertThat(stateStore.tasks).isEmpty();
    }

    private static SeedInteractionService service(InMemoryStateStore stateStore, List<Double> scores) {
        return service(stateStore, new RecordingCommentWriter(), scores);
    }

    private static SeedInteractionService service(
            InMemoryStateStore stateStore,
            RecordingCommentWriter writer,
            List<Double> scores) {
        return new SeedInteractionService(
                stateStore,
                writer,
                new SeedInteractionPolicy(() -> 0.0),
                (account, context) -> "我觉得这里可以接着聊一下：" + context.postTitle(),
                new SequentialQualityEvaluator(scores),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new FixedDelayPlanner());
    }

    private static SeedPostInteraction post() {
        return new SeedPostInteraction(
                1L,
                100L,
                "接口超时排查：从日志到连接池",
                List.of("技术", "Java", "后端"),
                "这是一篇关于 Java 后端排查的文章");
    }

    private static List<SeedInteractionAccount> accounts() {
        return List.of(
                account(100L, "author", List.of("技术")),
                account(200L, "yukino", List.of("技术文章", "编程", "动漫深度解析")),
                account(300L, "chinatsu", List.of("热门话题", "生活趣事", "新番推荐")),
                account(400L, "kazahana", List.of("书籍推荐", "文学评论", "历史")));
    }

    private static SeedInteractionAccount account(long id, String handle, List<String> tags) {
        return new SeedInteractionAccount(
                id,
                new SeedAccountProfile(
                        handle,
                        handle,
                        "bio",
                        "/avatar.png",
                        "SECRET",
                        LocalDate.of(2000, 1, 1),
                        "school",
                        tags,
                        handle + " persona"));
    }

    private static final class FixedDelayPlanner implements SeedInteractionDelayPlanner {
        @Override
        public Duration delayForRound(int round) {
            return switch (round) {
                case 1 -> Duration.ofMinutes(5);
                case 2 -> Duration.ofMinutes(10);
                default -> Duration.ofMinutes(30);
            };
        }
    }

    private static final class SequentialQualityEvaluator implements SeedInteractionQualityEvaluator {
        private final List<Double> scores;
        private int index;

        private SequentialQualityEvaluator(List<Double> scores) {
            this.scores = scores;
        }

        @Override
        public double evaluate(SeedInteractionAccount account, SeedInteractionContext context, String comment) {
            double score = scores.get(Math.min(index, scores.size() - 1));
            index++;
            return score;
        }
    }

    private static final class RecordingCommentWriter implements SeedInteractionCommentWriter {
        private long nextId = 10L;
        private final List<WrittenComment> comments = new ArrayList<>();

        @Override
        public long writeComment(long postId, Long parentCommentId, long userId, String content, Instant createdAt) {
            long id = nextId++;
            comments.add(new WrittenComment(id, postId, parentCommentId, userId, content, createdAt));
            return id;
        }
    }

    private record WrittenComment(
            long id,
            long postId,
            Long parentId,
            long userId,
            String content,
            Instant createdAt) {
    }

    private static final class InMemoryStateStore implements SeedInteractionStateStore {
        private final List<SeedInteractionTask> tasks = new ArrayList<>();
        private final Map<Long, Integer> rounds = new HashMap<>();
        private final Map<Long, Integer> commentCounts = new HashMap<>();
        private final Map<Long, Set<Long>> commenters = new HashMap<>();

        @Override
        public int currentRounds(long postId) {
            return rounds.getOrDefault(postId, 0);
        }

        @Override
        public int commentCount(long postId) {
            return commentCounts.getOrDefault(postId, 0);
        }

        @Override
        public boolean hasCommented(long postId, long userId) {
            return commenters.getOrDefault(postId, Set.of()).contains(userId);
        }

        @Override
        public long dailyComments(long userId, LocalDate date) {
            return 0;
        }

        @Override
        public void rememberComment(long postId, long userId, LocalDate date) {
            rounds.merge(postId, 1, Integer::sum);
            commentCounts.merge(postId, 1, Integer::sum);
            commenters.computeIfAbsent(postId, ignored -> new HashSet<>()).add(userId);
        }

        @Override
        public void enqueue(SeedInteractionTask task) {
            tasks.add(task);
        }

        @Override
        public List<SeedInteractionTask> dueTasks(Instant now, int limit) {
            return tasks.stream()
                    .filter(task -> !task.scheduledAt().isAfter(now))
                    .sorted(Comparator.comparing(SeedInteractionTask::scheduledAt))
                    .limit(limit)
                    .toList();
        }

        @Override
        public void removeTask(String taskId) {
            tasks.removeIf(task -> task.id().equals(taskId));
        }
    }
}
