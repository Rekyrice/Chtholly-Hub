package com.chtholly.seed;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Stores seed interaction queue and per-post/account counters.
 */
public interface SeedInteractionStateStore {

    int currentRounds(long postId);

    int commentCount(long postId);

    boolean hasCommented(long postId, long userId);

    long dailyComments(long userId, LocalDate date);

    void rememberComment(long postId, long userId, LocalDate date);

    void enqueue(SeedInteractionTask task);

    List<SeedInteractionTask> dueTasks(Instant now, int limit);

    void removeTask(String taskId);
}
