package com.chtholly.agent.eval;

import java.util.List;

/**
 * 人格一致性重复测试的结果。
 *
 * @param responses 多次 Agent 回复
 * @param score 风格一致性评分
 */
public record ConsistencyEvaluationResult(List<String> responses, EvaluationScore score) {
}
