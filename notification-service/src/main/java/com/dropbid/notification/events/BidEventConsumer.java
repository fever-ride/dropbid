package com.dropbid.notification.events;

import com.dropbid.shared.events.BidPlacedEvent;
import com.dropbid.shared.streaming.ResilientStreamConsumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
public class BidEventConsumer extends ResilientStreamConsumer {

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
            stomp.convertAndSend("/topic/auction/" + event.auctionId(), event);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
