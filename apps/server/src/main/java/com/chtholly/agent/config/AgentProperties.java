package com.chtholly.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Agent 运行参数。 */
@Data
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {
    /** ReAct 最大步数，防止无限循环。 */
    private int maxSteps = 5;
    /** 会话记忆保留的最大轮次（user+assistant 各算一条）。 */
    private int memoryMaxTurns = 20;
    /** 会话记忆 Redis TTL（分钟，滑动过期：每次读写刷新）。 */
    private int memoryTtlMinutes = 120;
    /** 流式输出每个字符间隔毫秒（0 表示不节流）。 */
    private int streamCharDelayMs = 50;
}
