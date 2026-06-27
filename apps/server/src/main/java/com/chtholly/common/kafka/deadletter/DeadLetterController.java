package com.chtholly.common.kafka.deadletter;

import com.chtholly.auth.token.JwtService;
import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.common.kafka.DeadLetterStatus;
import com.chtholly.config.SiteProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 死信消息管理接口（仅站点 owner 可访问）。
 */
@RestController
@RequestMapping("/api/v1/admin/dead-letters")
@RequiredArgsConstructor
public class DeadLetterController {

    private final DeadLetterMessageService deadLetterMessageService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final SiteProperties siteProperties;
    private final JwtService jwtService;

    @GetMapping
    public DeadLetterPageResponse list(@AuthenticationPrincipal Jwt jwt,
                                       @RequestParam(required = false) String topic,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        assertOwner(jwt);
        List<DeadLetterResponse> items = deadLetterMessageService.list(topic, status, page, size)
                .stream()
                .map(DeadLetterResponse::from)
                .toList();
        long total = deadLetterMessageService.count(topic, status);
        return new DeadLetterPageResponse(items, total, page, size);
    }

    @PostMapping("/{id}/replay")
    public DeadLetterResponse replay(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        assertOwner(jwt);
        DeadLetterMessageRow row = deadLetterMessageService.findById(id);
        if (row == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "死信消息不存在");
        }
        kafkaTemplate.send(row.getSourceTopic(), row.getMessageKey(), row.getMessageValue());
        deadLetterMessageService.updateStatus(id, DeadLetterStatus.PENDING);
        DeadLetterMessageRow updated = deadLetterMessageService.findById(id);
        return DeadLetterResponse.from(updated);
    }

    private void assertOwner(Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        if (userId != siteProperties.ownerUserId()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅管理员可操作死信消息");
        }
    }
}
