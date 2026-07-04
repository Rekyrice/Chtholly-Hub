package com.chtholly.agent.content;

/**
 * Entity extracted from a post body.
 *
 * @param name       entity name
 * @param category   entity category
 * @param confidence extraction confidence from 0.0 to 1.0
 */
public record Entity(String name, String category, double confidence) {
}
