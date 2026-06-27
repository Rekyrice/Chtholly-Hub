package com.chtholly.post.listener;

import com.chtholly.post.event.PostPublishedEvent;
import com.chtholly.post.feed.FeedTimelineService;
import com.chtholly.relation.event.FollowCanceledEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 监听发帖与取关事件，维护关注时间线 Redis 数据。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedTimelineListener {

    private static final Set<String> FEED_VISIBLE = Set.of("public", "followers");

    private final FeedTimelineService feedTimelineService;

    @Async("notificationExecutor")
    @EventListener
    public void onPostPublished(PostPublishedEvent event) {
        if (event.visible() == null || !FEED_VISIBLE.contains(event.visible())) {
            return;
        }
        if (event.publishTime() == null) {
            log.warn("feed.timeline skip postId={} missing publishTime", event.postId());
            return;
        }
        try {
            feedTimelineService.onPostPublished(event.creatorId(), event.postId(), event.publishTime());
        } catch (Exception e) {
            log.warn("feed.timeline push failed postId={} creatorId={}: {}",
                    event.postId(), event.creatorId(), e.getMessage(), e);
        }
    }

    @Async("notificationExecutor")
    @EventListener
    public void onFollowCanceled(FollowCanceledEvent event) {
        try {
            feedTimelineService.removeAuthorFromTimeline(event.fromUserId(), event.toUserId());
        } catch (Exception e) {
            log.warn("feed.timeline unfollow cleanup failed from={} to={}: {}",
                    event.fromUserId(), event.toUserId(), e.getMessage(), e);
        }
    }
}
