package com.chtholly.agent.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Map;

/** Evaluates the conjunction declared by {@link ConditionalOnAgentExtensions}. */
public class OnAgentExtensionsCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Map<String, Object> attributes = metadata.getAnnotationAttributes(
                ConditionalOnAgentExtensions.class.getName());
        if (attributes == null) {
            return false;
        }
        AgentExtensionGroup[] groups = (AgentExtensionGroup[]) attributes.get("value");
        if (groups == null || groups.length == 0) {
            return true;
        }
        for (AgentExtensionGroup group : groups) {
            // 属性缺失时保持历史默认启用语义，与 AgentExtensionProperties.Toggle 一致。
            if (!context.getEnvironment().getProperty(group.enabledProperty(), Boolean.class, true)) {
                return false;
            }
        }
        return true;
    }
}
