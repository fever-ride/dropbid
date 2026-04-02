package com.dropbid.notification.controller;

import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

/**
 * Provides both WebSocket (via STOMP) and SSE endpoints for real-time updates.
 *
 *  WebSocket: ws://host:8080/ws  (STOMP — subscribe to /topic/auction/{id})
 *  SSE:       GET /notifications/auction/{id}/stream
 */
@RestController
@RequestMapping("/notifications")
public class NotificationController {

    // SSE emitters indexed by auctionId
    private final ConcurrentHashMap<String, List<SseEmitter>> sseEmitters = new ConcurrentHashMap<>();

    private final SimpMessagingTemplate stomp;

    public NotificationController(SimpMessagingTemplate stomp) {
        this.stomp = stomp;
    }

    /**
     * SSE endpoint for clients that cannot use WebSocket.
     * Connection stays open; server pushes bid updates as text/event-stream.
     */
    @GetMapping(path = "/auction/{auctionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamAuction(@PathVariable String auctionId) {
        SseEmitter emitter = new SseEmitter(0L); // no timeout

        sseEmitters.computeIfAbsent(auctionId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        Runnable remove = () -> {
            List<SseEmitter> list = sseEmitters.get(auctionId);
            if (list != null) list.remove(emitter);
        };
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());

        return emitter;
    }

    /** Broadcast a bid event to all SSE listeners for an auction (called by BidEventConsumer). */
    public void broadcastToSse(String auctionId, Object payload) {
        List<SseEmitter> emitters = sseEmitters.get(auctionId);
        if (emitters == null || emitters.isEmpty()) return;

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(payload);
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        }
    }

    /** GET /notifications/health */
    @GetMapping("/health")
    public Map<String, Object> health() {
        int totalSse = sseEmitters.values().stream().mapToInt(List::size).sum();
        return Map.of("status", "UP", "sseConnections", totalSse);
    }
}
