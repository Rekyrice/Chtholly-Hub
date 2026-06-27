package com.chtholly.agent.api;

import com.chtholly.agent.memory.AgentMemoryStats;
import com.chtholly.agent.memory.AgentMemoryStore;
import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.config.SiteProperties;
import com.chtholly.auth.token.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Agent 运维统计（仅站点 owner 可访问）。 */
@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentStatsController {

    private final AgentMemoryStore memoryStore;
    private final SiteProperties siteProperties;
    private final JwtService jwtService;

    @GetMapping("/stats")
    public AgentMemoryStats stats(@AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        if (userId != siteProperties.ownerUserId()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅管理员可查看 Agent 统计");
        }
        return memoryStore.getStats();
    }
}
