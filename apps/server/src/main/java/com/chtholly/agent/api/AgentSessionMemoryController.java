package com.chtholly.agent.api;

import com.chtholly.agent.memory.AgentMemoryStore;
import com.chtholly.agent.ws.AgentChatSessionSupport;
import com.chtholly.auth.token.JwtService;
import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Provides authenticated, user-scoped lifecycle operations for Agent session memory. */
@RestController
@RequestMapping("/api/v1/agent/sessions")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentSessionMemoryController {

    private final AgentMemoryStore memoryStore;
    private final JwtService jwtService;

    /**
     * Idempotently clears the authenticated user's memory for one frontend session.
     *
     * @param jwt authenticated user JWT
     * @param sessionId frontend Agent session ID
     */
    @DeleteMapping("/{sessionId}/memory")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearMemory(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("sessionId") String sessionId) {
        if (!AgentChatSessionSupport.isValid(sessionId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Agent 会话 ID 格式无效");
        }
        long userId = jwtService.extractUserId(jwt);
        memoryStore.clearMemory(userId, sessionId);
    }
}
