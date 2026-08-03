package com.chtholly.agent.ws;

import org.springframework.util.StringUtils;

/** Validates frontend Agent chat session IDs shared by HTTP and WebSocket entry points. */
public final class AgentChatSessionSupport {

    private static final int MAX_LENGTH = 128;

    private AgentChatSessionSupport() {
    }

    /**
     * Checks whether a session ID is safe to use as an Agent memory key component.
     *
     * @param sessionId frontend chat session ID
     * @return {@code true} when the ID satisfies the shared format contract
     */
    public static boolean isValid(String sessionId) {
        if (!StringUtils.hasText(sessionId) || sessionId.length() > MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < sessionId.length(); i++) {
            char c = sessionId.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                continue;
            }
            return false;
        }
        return true;
    }
}
