package com.chtholly.recommendation;

import com.chtholly.counter.event.CounterEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 监听点赞/收藏事件，增量更新兴趣画像。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserInterestProfileListener {

    private final UserInterestProfile userInterestProfile;

    @EventListener
    public void onCounterEvent(CounterEvent event) {
        if (event == null || event.getDelta() <= 0) {
            return;
        }
        if (!"post".equals(event.getEntityType())) {
            return;
        }
        String metric = event.getMetric();
        if (!"like".equals(metric) && !"fav".equals(metric)) {
            return;
        }
        try {
            long postId = Long.parseLong(event.getEntityId());
            userInterestProfile.updateProfile(event.getUserId(), postId, metric);
        } catch (NumberFormatException e) {
            log.debug("跳过非数字帖子 ID: {}", event.getEntityId());
        }
    }
}
