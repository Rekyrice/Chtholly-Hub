package com.chtholly.notification.listener;

import com.chtholly.counter.event.CounterEvent;
import com.chtholly.notification.event.CommentCreatedEvent;
import com.chtholly.notification.event.FollowCreatedEvent;
import com.chtholly.notification.model.NotificationType;
import com.chtholly.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.Map;

/** 监听业务事件；评论/关注通知异步写入，互动通知同步参与事件级回执。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventListener {

    private final NotificationService notificationService;

    @TransactionalEventListener(
            phase = TransactionPhase.BEFORE_COMMIT)
    public void onCommentCreated(CommentCreatedEvent event) {
        try {
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
        } catch (Exception ex) {
            log.error("评论通知写入失败 commentId={}: {}", event.commentId(), ex.getMessage(), ex);
            if (ex instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Comment notification persistence failed", ex);
        }
    }

    @TransactionalEventListener(
            phase = TransactionPhase.BEFORE_COMMIT)
    public void onFollowCreated(FollowCreatedEvent event) {
        try {
            if (event.fromUserId() == event.toUserId()) {
                return;
            }
            Map<String, Object> payload = basePayload(event.fromUserId(), event.fromNickname(), event.fromAvatar());
            notificationService.create(event.toUserId(), NotificationType.FOLLOW, payload);
        } catch (Exception ex) {
            log.error("关注通知写入失败 fromUserId={} toUserId={}: {}",
                    event.fromUserId(), event.toUserId(), ex.getMessage(), ex);
            if (ex instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Follow notification persistence failed", ex);
        }
    }

    @EventListener
    public void onCounterEvent(CounterEvent event) {
        try {
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
        } catch (Exception ex) {
            log.error("点赞通知写入失败 postId={} actorUserId={}: {}",
                    event.getEntityId(), event.getUserId(), ex.getMessage(), ex);
            if (ex instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Reaction notification persistence failed", ex);
        }
    }

    private Map<String, Object> basePayload(long actorUserId, String nickname, String avatar) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("actorUserId", actorUserId);
        payload.put("actorNickname", nickname);
        payload.put("actorAvatar", avatar);
        return payload;
    }
}
