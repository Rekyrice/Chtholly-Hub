package com.chtholly.agent.config;

import com.chtholly.agent.ws.AgentWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
@EnableConfigurationProperties(AgentProperties.class)
public class AgentWebSocketConfig implements WebSocketConfigurer {

    static final int TEXT_MESSAGE_SIZE_LIMIT = 8192;
    static final int SEND_TIME_LIMIT_MS = 10_000;

    private final AgentWebSocketHandler agentWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(agentWebSocketHandler, "/api/v1/agent/ws")
                .setAllowedOrigins("*");
    }

    /** 限制入站消息大小与发送超时（Tomcat WebSocket 容器级）。 */
    @Bean
    @ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
    public ServletServerContainerFactoryBean agentWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(TEXT_MESSAGE_SIZE_LIMIT);
        container.setMaxBinaryMessageBufferSize(TEXT_MESSAGE_SIZE_LIMIT);
        container.setAsyncSendTimeout((long) SEND_TIME_LIMIT_MS);
        return container;
    }
}
