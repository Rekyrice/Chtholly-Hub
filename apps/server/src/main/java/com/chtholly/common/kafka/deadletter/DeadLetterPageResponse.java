package com.chtholly.common.kafka.deadletter;

import java.util.List;

/** 死信消息分页响应。 */
public record DeadLetterPageResponse(
        List<DeadLetterResponse> items,
        long total,
        int page,
        int size
) {}
