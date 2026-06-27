package com.chtholly.notification.listener;

import com.chtholly.counter.event.CounterEvent;
import com.chtholly.notification.event.CommentCreatedEvent;
import com.chtholly.notification.event.FollowCreatedEvent;
import com.chtholly.notification.model.NotificationType;
import com.chtholly.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 监听业务事件并异步写入通知。 */
@Component
@RequiredArgsConstructor
public class NotificationEventListener {

    private final NotificationService notificationService;

    @Async("notificationExecutor")
    @EventListener
    public void onCommentCreated(CommentCreatedEvent event) {
        Map<String, Object> base = basePayload(event.authorUserId(), event.authorNickname(), event.authorAvatar());
        base.put("postId", event.postId());
        base.put("postSlug", event.postSlug());
        base.put("postTitle", event.postTitle());
        base.put("commentId", event.commentId());

        if (event.parentId() != null && event.parentCommentUserId() != null) {
            long recipient = event.parentCommentUserId();
            if (recipient != event.authorUserId()) {
                notificationService.create(recipient, NotificationType.COMMENT_REPLY, base);
            }
            return;
        }

        long recipient = event.postCreatorId();
        if (recipient != event.authorUserId()) {
            notificationService.create(recipient, NotificationType.COMMENT_POST, base);
        }
    }

    @Async("notificationExecutor")
    @EventListener
    public void onFollowCreated(FollowCreatedEvent event) {
        if (event.fromUserId() == event.toUserId()) {
            return;
        }
        Map<String, Object> payload = basePayload(event.fromUserId(), event.fromNickname(), event.fromAvatar());
        notificationService.create(event.toUserId(), NotificationType.FOLLOW, payload);
    }

    @Async("notificationExecutor")
    @EventListener
    public void onCounterEvent(CounterEvent event) {
        if (!"like".equals(event.getMetric()) || event.getDelta() != 1) {
            return;
        }
        if (!"post".equals(event.getEntityType())) {
            return;
        }
        if (event.getPostCreatorId() == null) {
            return;
        }

        long postId;
        try {
            postId = Long.parseLong(event.getEntityId());
        } catch (NumberFormatException e) {
            return;
        }

        long recipient = event.getPostCreatorId();
        if (recipient == event.getUserId()) {
            return;
        }

        if (notificationService.hasUnreadLikePost(recipient, postId)) {
            return;
        }

        Map<String, Object> payload = basePayload(
                event.getUserId(),
                event.getActorNickname(),
                event.getActorAvatar()
        );
        payload.put("postId", postId);
        payload.put("postSlug", event.getPostSlug());
        payload.put("postTitle", event.getPostTitle());
        notificationService.create(recipient, NotificationType.LIKE_POST, payload);
    }

    private Map<String, Object> basePayload(long actorUserId, String nickname, String avatar) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("actorUserId", actorUserId);
        payload.put("actorNickname", nickname);
        payload.put("actorAvatar", avatar);
        return payload;
    }
}
