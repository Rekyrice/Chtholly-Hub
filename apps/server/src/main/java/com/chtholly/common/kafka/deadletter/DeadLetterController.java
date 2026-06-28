package com.chtholly.common.kafka.deadletter;

import com.chtholly.admin.role.RequireRole;
import com.chtholly.admin.role.Role;
import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.common.kafka.DeadLetterStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 死信消息管理接口（仅 ADMIN 可访问）。
 */
@RestController
@RequestMapping("/api/v1/admin/dead-letters")
@RequireRole(Role.ADMIN)
@RequiredArgsConstructor
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true")
public class DeadLetterController {

    private final DeadLetterMessageService deadLetterMessageService;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @GetMapping
    public DeadLetterPageResponse list(@RequestParam(required = false) String topic,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        List<DeadLetterResponse> items = deadLetterMessageService.list(topic, status, page, size)
                .stream()
                .map(DeadLetterResponse::from)
                .toList();
        long total = deadLetterMessageService.count(topic, status);
        return new DeadLetterPageResponse(items, total, page, size);
    }

    @PostMapping("/{id}/replay")
    public DeadLetterResponse replay(@PathVariable long id) {
        DeadLetterMessageRow row = deadLetterMessageService.findById(id);
        if (row == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "死信消息不存在");
        }
        kafkaTemplate.send(row.getSourceTopic(), row.getMessageKey(), row.getMessageValue());
        deadLetterMessageService.updateStatus(id, DeadLetterStatus.PENDING);
        DeadLetterMessageRow updated = deadLetterMessageService.findById(id);
        return DeadLetterResponse.from(updated);
    }
}
