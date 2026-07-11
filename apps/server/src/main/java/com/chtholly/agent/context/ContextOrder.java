package com.chtholly.agent.context;

/** Canonical ordering of system-prompt contributors. */
public final class ContextOrder {

    public static final int IDENTITY = 100;
    public static final int RELATIONSHIP = 200;
    public static final int PAGE = 300;
    public static final int KNOWLEDGE = 400;
    public static final int PROCEDURAL = 500;
    public static final int TOOLS = 600;
    public static final int HISTORY = 700;
    public static final int QUESTION = 800;

    private ContextOrder() {
    }
}
