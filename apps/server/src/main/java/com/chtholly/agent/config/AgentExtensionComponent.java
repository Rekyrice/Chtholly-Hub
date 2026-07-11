package com.chtholly.agent.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Spring entry point as belonging to the optional agent extension boundary.
 *
 * <p>The marker intentionally carries no component stereotype, so it cannot change
 * how a class is registered by Spring.
 */
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AgentExtensionComponent {
}
