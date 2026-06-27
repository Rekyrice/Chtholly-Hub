package com.chtholly.notification.listener;

import com.chtholly.counter.event.CounterEvent;
import com.chtholly.notification.event.CommentCreatedEvent;
import com.chtholly.notification.event.FollowCreatedEvent;
import com.chtholly.notification.model.NotificationType;
import com.chtholly.notification.service.NotificationService;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.Post;
import com.chtholly.user.domain.User;
import com.chtholly.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 监听业务事件并写入通知（Kafka 降级为 Spring ApplicationEvent）。 */
@Component
@RequiredArgsConstructor
public class NotificationEventListener {

    private final NotificationService notificationService;
    private final PostMapper postMapper;
    private final UserMapper userMapper;

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

    @EventListener
    public void onFollowCreated(FollowCreatedEvent event) {
        if (event.fromUserId() == event.toUserId()) {
            return;
        }
        Map<String, Object> payload = basePayload(event.fromUserId(), event.fromNickname(), event.fromAvatar());
        notificationService.create(event.toUserId(), NotificationType.FOLLOW, payload);
    }

    @EventListener
    public void onCounterEvent(CounterEvent event) {
        if (!"like".equals(event.getMetric()) || event.getDelta() != 1) {
            return;
        }
        if (!"post".equals(event.getEntityType())) {
            return;
        }

        long postId;
        try {
            postId = Long.parseLong(event.getEntityId());
        } catch (NumberFormatException e) {
            return;
        }

        Post post = postMapper.findById(postId);
        if (post == null || post.getCreatorId() == null) {
            return;
        }
        long recipient = post.getCreatorId();
        if (recipient == event.getUserId()) {
            return;
        }

        User actor = userMapper.findById(event.getUserId());
        Map<String, Object> payload = basePayload(
                event.getUserId(),
                actor == null ? null : actor.getNickname(),
                actor == null ? null : actor.getAvatar()
        );
        payload.put("postId", postId);
        payload.put("postSlug", post.getSlug());
        payload.put("postTitle", post.getTitle());
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
