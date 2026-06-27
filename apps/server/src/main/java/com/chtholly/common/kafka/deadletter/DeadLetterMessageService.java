package com.chtholly.common.kafka.deadletter;

import com.chtholly.common.kafka.DeadLetterStatus;
import com.chtholly.post.id.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 死信消息持久化与查询服务。
 */
@Service
@RequiredArgsConstructor
public class DeadLetterMessageService {

    private final DeadLetterMessageMapper mapper;
    private final SnowflakeIdGenerator idGen;

    /**
     * 记录一次消费失败。
     */
    public long recordFailure(String sourceTopic,
                              String messageKey,
                              String messageValue,
                              Exception exception,
                              int retryCount,
                              DeadLetterStatus status) {
        long id = idGen.nextId();
        mapper.insert(
                id,
                sourceTopic,
                messageKey,
                messageValue,
                exception.getClass().getName(),
                exception.getMessage(),
                retryCount,
                status.name());
        return id;
    }

    public DeadLetterMessageRow findById(long id) {
        return mapper.findById(id);
    }

    public List<DeadLetterMessageRow> list(String topic, String status, int page, int size) {
        int limit = Math.max(1, Math.min(size, 100));
        int offset = Math.max(0, (Math.max(page, 1) - 1) * limit);
        return mapper.list(topic, status, limit, offset);
    }

    public long count(String topic, String status) {
        return mapper.count(topic, status);
    }

    public void updateStatus(long id, DeadLetterStatus status) {
        mapper.updateStatus(id, status.name());
    }
}
