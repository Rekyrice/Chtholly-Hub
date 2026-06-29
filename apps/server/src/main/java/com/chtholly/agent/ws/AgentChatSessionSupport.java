package com.chtholly.agent.ws;

import org.springframework.util.StringUtils;

/** 校验前端 Agent 会话 id（与 localStorage sess-xxx 一致）。 */
final class AgentChatSessionSupport {

    private static final int MAX_LENGTH = 128;

    private AgentChatSessionSupport() {
    }

    static boolean isValid(String sessionId) {
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
