package com.chtholly.agent;

import java.util.Collections;
import java.util.Map;

/** Agent 可调用的工具。 */
public interface AgentTool {

    /** 工具标识，与 LLM JSON 中 action 字段一致。 */
    String name();

    /** 供 LLM 理解的工具说明。 */
    String description();

    /** LLM 可见参数的 schema；默认空 map 表示不做声明式校验。 */
    default Map<String, ParamDef> parameterSchema() {
        return Collections.emptyMap();
    }

    /** 执行工具并返回观察结果（纯文本）。 */
    String execute(Map<String, Object> input, long userId);
}
