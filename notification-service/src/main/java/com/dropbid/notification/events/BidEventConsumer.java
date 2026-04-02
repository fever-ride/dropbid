package com.dropbid.notification.events;

import com.dropbid.shared.events.BidPlacedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Consumes the {@code bid_placed} Redis Stream (consumer group: {@code notification-service})
 * and forwards each event to the STOMP topic {@code /topic/auction/{auctionId}}.
 *
 * Uses a separate consumer group from the Bid Service so both receive every event.
 */
@Component
public class BidEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(BidEventConsumer.class);

    private static final String STREAM        = "bid_placed";
    private static final String GROUP         = "notification-service";
    private static final String CONSUMER_NAME = "notif-consumer-1";
    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(2);

    private final StringRedisTemplate    redis;
    private final SimpMessagingTemplate  stomp;
    private final ObjectMapper           mapper;

    public BidEventConsumer(StringRedisTemplate redis,
                             SimpMessagingTemplate stomp,
                             ObjectMapper mapper) {
        this.redis  = redis;
        this.stomp  = stomp;
        this.mapper = mapper;
    }

    @PostConstruct
    void start() {
        ensureConsumerGroup();
        Thread.ofVirtual().name("notif-event-consumer").start(this::consumeLoop);
    }

    private void consumeLoop() {
        log.info("Notification consumer started on stream={} group={}", STREAM, GROUP);
        while (!Thread.currentThread().isInterrupted()) {
            try {
                List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                        Consumer.from(GROUP, CONSUMER_NAME),
                        StreamReadOptions.empty().count(20).block(BLOCK_TIMEOUT),
                        StreamOffset.create(STREAM, ReadOffset.lastConsumed())
                );

                if (records == null || records.isEmpty()) continue;

                for (MapRecord<String, Object, Object> record : records) {
                    processRecord(record);
                }
            } catch (Exception e) {
                log.error("Error in notification consumer: {}", e.getMessage(), e);
                try { Thread.sleep(500); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void processRecord(MapRecord<String, Object, Object> record) {
        try {
            String json  = (String) record.getValue().get("data");
            BidPlacedEvent event = mapper.readValue(json, BidPlacedEvent.class);

            // Push to all subscribers of this auction's STOMP topic
            stomp.convertAndSend("/topic/auction/" + event.auctionId(), event);

            redis.opsForStream().acknowledge(STREAM, GROUP, record.getId());
            log.debug("pushed bid update auction={} amount={}", event.auctionId(), event.amount());
        } catch (Exception e) {
            log.error("Failed to push notification for record {}: {}", record.getId(), e.getMessage(), e);
        }
    }

    private void ensureConsumerGroup() {
        try {
            redis.opsForStream().createGroup(STREAM, ReadOffset.from("0"), GROUP);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                log.debug("Consumer group {} already exists", GROUP);
            } else {
                log.warn("Could not create consumer group {}: {}", GROUP, e.getMessage());
            }
        }
    }
}
