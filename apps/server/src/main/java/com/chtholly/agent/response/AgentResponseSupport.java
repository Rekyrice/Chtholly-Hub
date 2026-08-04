package com.chtholly.agent.response;

import com.chtholly.agent.runtime.AgentTurnBudget;

import java.util.concurrent.TimeoutException;

/** Shared low-level classification and timing helpers for response-generation stages. */
final class AgentResponseSupport {

    private AgentResponseSupport() {
    }

    static boolean isTimeout(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof TimeoutException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase().contains("timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    static long remainingBudgetMs(AgentTurnBudget budget) {
        if (budget == null) {
            return 0L;
        }
        return Math.max(
                0L,
                budget.totalBudget().toMillis() - budget.elapsed().toMillis());
    }

    static int saturatedCharCount(long chars) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, chars));
    }
}
