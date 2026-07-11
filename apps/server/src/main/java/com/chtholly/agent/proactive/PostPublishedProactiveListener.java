package com.chtholly.agent.proactive;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

import com.chtholly.agent.notification.Notification;
import com.chtholly.agent.notification.NotificationChannel;
import com.chtholly.post.event.PostPublishedEvent;
import com.chtholly.post.service.PostService;
import com.chtholly.post.api.dto.PostDetailResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 新文章发布时，向兴趣标签匹配的用户推送「信息主动」通知。
 */
@Component
@ConditionalOnExpression("${agent.extensions.proactive.enabled:true} && ${agent.extensions.experience.enabled:true} && ${agent.extensions.community-actions.enabled:true}")
@RequiredArgsConstructor
@Slf4j
public class PostPublishedProactiveListener {

    private final PostService postService;
    private final CharacterStateUserActivityProvider activityProvider;
    private final ProactiveNotificationDispatcher dispatcher;

    @EventListener
    public void onPostPublished(PostPublishedEvent event) {
        if (event == null || event.postId() <= 0) {
            return;
        }
        if (event.visible() != null && !"public".equalsIgnoreCase(event.visible())) {
            return;
        }
        PostDetailResponse detail = postService.getDetail(event.postId(), null);
        if (detail == null || detail.title() == null || detail.title().isBlank()) {
            return;
        }
        List<String> tags = detail.tags() == null ? List.of() : detail.tags();
        List<Long> recipients = activityProvider.findUsersInterestedInTags(tags, event.creatorId());
        if (recipients.isEmpty()) {
            recipients = activityProvider.findActiveUserIds().stream()
                    .filter(id -> id != event.creatorId())
                    .toList();
        }
        String message = "有人带了新故事来：" + detail.title();
        for (Long userId : recipients) {
            boolean sent = dispatcher.send(
                    userId,
                    new Notification("new-content", message, NotificationChannel.AGENT_PAGE),
                    ProactiveNotificationDispatcher.Category.RECOMMEND);
            if (sent) {
                log.debug("新内容推送 userId={} postId={}", userId, event.postId());
            }
        }
    }
}
