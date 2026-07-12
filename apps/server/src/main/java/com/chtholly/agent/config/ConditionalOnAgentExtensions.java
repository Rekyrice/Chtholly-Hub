package com.chtholly.agent.config;

import org.springframework.context.annotation.Conditional;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Registers an extension component only when every declared extension is enabled. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@AgentExtensionComponent
@Conditional(OnAgentExtensionsCondition.class)
public @interface ConditionalOnAgentExtensions {

    /**
     * Lists the extension switches required by the annotated component.
     *
     * @return required extension groups
     */
    AgentExtensionGroup[] value();
}
