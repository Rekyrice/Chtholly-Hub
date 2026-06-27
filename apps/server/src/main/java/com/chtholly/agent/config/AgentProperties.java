package com.chtholly.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Agent 运行参数。 */
@Data
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {
    /** ReAct 最大步数，防止无限循环。 */
    private int maxSteps = 5;
}
