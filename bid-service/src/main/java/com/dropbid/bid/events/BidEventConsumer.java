package com.dropbid.bid.events;

import com.dropbid.bid.service.BidService;
import com.dropbid.shared.events.BidPlacedEvent;
import com.dropbid.shared.streaming.ResilientStreamConsumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class BidEventConsumer extends ResilientStreamConsumer {

    private final BidService bidService;
    private final ObjectMapper mapper;

    public BidEventConsumer(StringRedisTemplate redis, BidService bidService, ObjectMapper mapper) {
        super(redis);
        this.bidService = bidService;
        this.mapper = mapper;
    }

    @Override protected String stream() { return "bid_placed"; }
    @Override protected String group() { return "bid-service"; }
    @Override protected String consumerName() { return "bid-consumer-1"; }

    @Override
    protected void handleMessage(MapRecord<String, Object, Object> record) {
        try {
            String json = (String) record.getValue().get("data");
            BidPlacedEvent event = mapper.readValue(json, BidPlacedEvent.class);
            bidService.recordBid(event);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
