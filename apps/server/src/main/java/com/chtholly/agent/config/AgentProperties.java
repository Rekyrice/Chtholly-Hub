package com.chtholly.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Agent 运行参数。 */
@Data
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {
    /** LLM 模型名（DeepSeek 等）。 */
    private String model = "deepseek-chat";
    /** 单次 LLM 调用超时（秒）。 */
    private int llmTimeoutSeconds = 30;
    /** 流式最终回答最大字符数（超出截断）。 */
    private int maxResponseChars = 2000;
    /** ReAct 最大步数，防止无限循环。 */
    private int maxSteps = 5;
    /** 会话记忆保留的最大轮次（user+assistant 各算一条）。 */
    private int memoryMaxTurns = 20;
    /** 会话记忆 Redis TTL（分钟，滑动过期：每次读写刷新）。 */
    private int memoryTtlMinutes = 120;
    /** 流式输出每个字符间隔毫秒（0 表示不节流）。 */
    private int streamCharDelayMs = 50;
    /** 单次工具执行超时（秒）。 */
    private int toolTimeoutSeconds = 30;
}
