package com.chtholly.agent.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Builds stable Redis keys shared by turn coordination and fenced side effects. */
public final class AgentTurnKeySupport {

    private static final String KEY_PREFIX = "agent:turn:";

    private AgentTurnKeySupport() {
    }

    public static String activeKey(long userId, String chatSessionId) {
        return KEY_PREFIX + "active:" + userId + ":" + sha256(requireText(chatSessionId));
    }

    public static String requestKey(long userId, String chatSessionId, String requestId) {
        return KEY_PREFIX + "request:" + userId + ":" + sha256(requireText(chatSessionId))
                + ":" + sha256(requireText(requestId));
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Agent turn key value must not be blank");
        }
        return value;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
