package com.dropbid.notification.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.*;

/**
 * Spring WebSocket + STOMP configuration.
 *
 * Clients connect to ws://host:8080/ws (SockJS fallback available).
 * After handshake they subscribe to /topic/auction/{auctionId} to receive
 * real-time bid updates.
 *
 * The simple in-memory broker is sufficient for a single-node deployment.
 * For multi-node, replace enableSimpleBroker with a RabbitMQ/Redis broker relay.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS(); // SockJS fallback for environments that block raw WS
    }
}
