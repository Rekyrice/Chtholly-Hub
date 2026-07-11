package com.chtholly.agent.proactive;

import com.chtholly.agent.config.AgentExtensionGroup;
import com.chtholly.agent.config.ConditionalOnAgentExtensions;

import com.chtholly.agent.notification.Notification;
import com.chtholly.agent.notification.NotificationService;
import com.chtholly.agent.state.BehaviorProb;
import com.chtholly.agent.state.CharacterState;
import com.chtholly.agent.state.CharacterStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 统一主动推送出口：频率上限 + BehaviorProb 概率门控。
 */
@Component
@ConditionalOnAgentExtensions({AgentExtensionGroup.PROACTIVE, AgentExtensionGroup.EXPERIENCE,
        AgentExtensionGroup.COMMUNITY_ACTIONS})
@RequiredArgsConstructor
public class ProactiveNotificationDispatcher {

    public enum Category {
        /** 问候/信息类：proactiveGreet */
        GREET,
        /** 观察分享：shareObservation */
        OBSERVATION,
        /** 内容推荐：recommendPost */
        RECOMMEND
    }

    private final NotificationService notificationService;
    private final ProactiveRateLimiter rateLimiter;
    private final CharacterStateService characterStateService;

    /**
     * 尝试向用户发送主动通知；受每日上限与行为概率控制。
     *
     * @return 是否实际发送
     */
    public boolean send(long userId, Notification notification, Category category) {
        if (userId <= 0 || notification == null) {
            return false;
        }
        if (!rateLimiter.canSend(userId)) {
            return false;
        }
        if (!passesBehaviorGate(userId, category)) {
            return false;
        }
        notificationService.send(userId, notification);
        rateLimiter.recordSend(userId);
        return true;
    }

    public int totalSentToday(long userId) {
        return rateLimiter.totalSentToday(userId);
    }

    private boolean passesBehaviorGate(long userId, Category category) {
        CharacterState state = characterStateService.load(userId);
        BehaviorProb prob = state.behaviorProb();
        double threshold = switch (category) {
            case GREET -> prob.proactiveGreet();
            case OBSERVATION -> prob.shareObservation();
            case RECOMMEND -> prob.recommendPost();
        };
        if (threshold >= 1.0) {
            return true;
        }
        if (threshold <= 0.0) {
            return false;
        }
        return ThreadLocalRandom.current().nextDouble() <= threshold;
    }
}
