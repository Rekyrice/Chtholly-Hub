package com.chtholly.agent.eval;

/**
 * Runtime options for an evaluation run.
 *
 * @param quickMode whether to use the quick subset
 * @param quickLimit number of questions in quick mode
 * @param userId synthetic user id used by the agent responder
 */
public record EvaluationRunOptions(boolean quickMode, int quickLimit, long userId) {

    public static EvaluationRunOptions quick() {
        return new EvaluationRunOptions(true, 10, 1L);
    }

    public static EvaluationRunOptions full() {
        return new EvaluationRunOptions(false, Integer.MAX_VALUE, 1L);
    }

    /**
     * 从 JVM 系统属性读取评测模式（供 run-eval.sh 使用）。
     */
    public static EvaluationRunOptions fromSystemProperties() {
        String mode = System.getProperty("eval.mode", "");
        long userId = Long.parseLong(System.getProperty("eval.userId", "1"));
        if ("full".equalsIgnoreCase(mode)) {
            return new EvaluationRunOptions(false, Integer.MAX_VALUE, userId);
        }
        if ("quick".equalsIgnoreCase(mode)) {
            int limit = Integer.parseInt(System.getProperty("eval.quickLimit", "10"));
            return new EvaluationRunOptions(true, limit, userId);
        }
        return quick();
    }
}
