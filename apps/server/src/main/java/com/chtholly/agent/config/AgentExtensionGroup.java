package com.chtholly.agent.config;

/** Optional agent extension switches addressable by combination conditions. */
public enum AgentExtensionGroup {
    CONTENT("content"),
    GRAPH("graph"),
    LEARNING("learning"),
    EXPERIENCE("experience"),
    MOOD("mood"),
    PROACTIVE("proactive"),
    COMMUNITY_ACTIONS("community-actions");

    private static final String PROPERTY_PREFIX = "agent.extensions.";

    private final String propertySegment;

    AgentExtensionGroup(String propertySegment) {
        this.propertySegment = propertySegment;
    }

    /**
     * Returns the complete enabled-property key for this extension.
     *
     * @return property key ending in {@code .enabled}
     */
    public String enabledProperty() {
        return PROPERTY_PREFIX + propertySegment + ".enabled";
    }
}
