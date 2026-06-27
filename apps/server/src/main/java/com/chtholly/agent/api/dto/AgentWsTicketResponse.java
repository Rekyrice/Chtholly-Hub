package com.chtholly.agent.api.dto;

/** WebSocket 握手 ticket 响应。 */
public record AgentWsTicketResponse(String ticket, int expiresInSeconds) {}
