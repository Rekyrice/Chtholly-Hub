package com.chtholly.agent.eval;

/**
 * One dimension score.
 *
 * @param score integer score from 1 to 5
 * @param reason judge-readable reason
 */
public record EvaluationScore(int score, String reason) {
}
