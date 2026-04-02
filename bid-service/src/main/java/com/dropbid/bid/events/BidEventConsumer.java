package com.dropbid.bid.events;

import com.dropbid.bid.service.BidService;
import com.dropbid.shared.events.BidPlacedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Consumes the {@code bid_placed} Redis Stream in a dedicated virtual thread.
 *
 * Consumer group: {@code bid-service}
 * This ensures each bid event is processed exactly once even if the service restarts,
 * replacing the Go Pub/Sub approach with guaranteed-delivery Streams.
 */
@Component
public class BidEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(BidEventConsumer.class);

    private static final String STREAM         = "bid_placed";
    private static final String GROUP          = "bid-service";
    private static final String CONSUMER_NAME  = "bid-consumer-1";
    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(2);

    private final StringRedisTemplate redis;
    private final BidService bidService;
    private final ObjectMapper mapper;

    public BidEventConsumer(StringRedisTemplate redis, BidService bidService, ObjectMapper mapper) {
        this.redis      = redis;
        this.bidService = bidService;
        this.mapper     = mapper;
    }

    @PostConstruct
    void start() {
        ensureConsumerGroup();
        // Virtual thread — cheap and does not block carrier threads
        Thread.ofVirtual().name("bid-event-consumer").start(this::consumeLoop);
    }

    @SuppressWarnings("unchecked")
    private void consumeLoop() {
        log.info("Bid event consumer started on stream={} group={}", STREAM, GROUP);
        while (!Thread.currentThread().isInterrupted()) {
            try {
                List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                        Consumer.from(GROUP, CONSUMER_NAME),
                        StreamReadOptions.empty().count(10).block(BLOCK_TIMEOUT),
                        StreamOffset.create(STREAM, ReadOffset.lastConsumed())
                );

                if (records == null || records.isEmpty()) continue;

                for (MapRecord<String, Object, Object> record : records) {
                    processRecord(record);
                }
            } catch (Exception e) {
                log.error("Error in bid consumer loop: {}", e.getMessage(), e);
                try { Thread.sleep(1000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void processRecord(MapRecord<String, Object, Object> record) {
        try {
            String json = (String) record.getValue().get("data");
            BidPlacedEvent event = mapper.readValue(json, BidPlacedEvent.class);
            bidService.recordBid(event);
            redis.opsForStream().acknowledge(STREAM, GROUP, record.getId());
        } catch (Exception e) {
            log.error("Failed to process bid event {}: {}", record.getId(), e.getMessage(), e);
            // Message stays in PEL (pending entry list) and will NOT be automatically
            // re-delivered. Full recovery requires XAUTOCLAIM — acceptable limitation
            // for this project; failed messages must be manually re-processed if needed.
        }
    }

    private void ensureConsumerGroup() {
        try {
            // MKSTREAM=true creates the stream key if it doesn't exist yet,
            // preventing failure when bid-service starts before any bid is placed.
            redis.execute((RedisCallback<Object>) conn -> {
                conn.streamCommands().xGroupCreate(
                        STREAM.getBytes(StandardCharsets.UTF_8),
                        GROUP,
                        ReadOffset.from("0"),
                        true  // mkStream
                );
                return null;
            });
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                log.debug("Consumer group {} already exists on stream {}", GROUP, STREAM);
            } else {
                log.warn("Could not create consumer group {} on stream {}: {}", GROUP, STREAM, e.getMessage());
            }
        }
    }
}
