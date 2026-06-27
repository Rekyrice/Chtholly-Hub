package com.chtholly.common.kafka;

/** 重试延迟策略：第 1/2/3 次重试分别延迟 5s / 30s / 120s。 */
public final class KafkaRetryDelays {

    private static final long[] DELAY_SECONDS = {5, 30, 120};

    private KafkaRetryDelays() {
    }

    /**
     * 计算下一次重试的 deliverAfter 时间戳。
     *
     * @param nextRetryCount 下一次重试序号（1-based）
     */
    public static long deliverAfterEpochMs(int nextRetryCount) {
        int index = Math.max(0, Math.min(nextRetryCount - 1, DELAY_SECONDS.length - 1));
        return System.currentTimeMillis() + DELAY_SECONDS[index] * 1000L;
    }
}
