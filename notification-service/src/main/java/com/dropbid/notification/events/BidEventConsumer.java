package com.dropbid.notification.events;

import com.dropbid.shared.events.BidPlacedEvent;
import com.dropbid.shared.streaming.ResilientStreamConsumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class BidEventConsumer extends ResilientStreamConsumer {

    private static final Duration DEDUP_TTL = Duration.ofHours(2);

    private final SimpMessagingTemplate stomp;
    private final ObjectMapper mapper;

    public BidEventConsumer(StringRedisTemplate redis,
                             SimpMessagingTemplate stomp,
                             ObjectMapper mapper) {
        super(redis);
        this.stomp = stomp;
        this.mapper = mapper;
    }

    @Override protected String stream() { return "bid_placed"; }
    @Override protected String group() { return "notification-service"; }
    @Override protected String consumerName() { return "notif-consumer-1"; }
    @Override protected int batchSize() { return 20; }

    @Override
    protected void handleMessage(MapRecord<String, Object, Object> record) {
        try {
            String json = (String) record.getValue().get("data");
            BidPlacedEvent event = mapper.readValue(json, BidPlacedEvent.class);

            // Deduplicate redelivered messages using bidId as the idempotency key.
            // setIfAbsent returns false if the key already exists — this is a replay.
            String dedupKey = "notif:dedup:" + event.bidId();
            Boolean isNew = redis.opsForValue().setIfAbsent(dedupKey, "1", DEDUP_TTL);
            if (!Boolean.TRUE.equals(isNew)) return;

            stomp.convertAndSend("/topic/auction/" + event.auctionId(), event);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
