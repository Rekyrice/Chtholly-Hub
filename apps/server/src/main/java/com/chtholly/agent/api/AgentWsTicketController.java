package com.chtholly.agent.api;

import com.chtholly.agent.api.dto.AgentWsTicketResponse;
import com.chtholly.agent.ws.AgentWsTicketStore;
import com.chtholly.auth.token.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

/** 为已登录用户签发 Agent WebSocket 短生命周期 ticket。 */
@RestController
@RequestMapping(path = "/api/v1/agent", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
@RequiredArgsConstructor
public class AgentWsTicketController {

    private final AgentWsTicketStore ticketStore;
    private final JwtService jwtService;

    @PostMapping("/ws-ticket")
    public AgentWsTicketResponse issueTicket(@AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        String ticket = ticketStore.create(userId);
        return new AgentWsTicketResponse(ticket, AgentWsTicketStore.TICKET_TTL_SECONDS);
    }
}
