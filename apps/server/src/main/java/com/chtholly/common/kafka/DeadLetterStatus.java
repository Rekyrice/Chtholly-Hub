package com.chtholly.common.kafka;

/** 死信消息处理状态。 */
public enum DeadLetterStatus {
    PENDING,
    RETRYING,
    DEAD
}
