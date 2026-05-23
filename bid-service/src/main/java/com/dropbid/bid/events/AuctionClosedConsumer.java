package com.dropbid.bid.events;

import com.dropbid.bid.service.BidService;
import com.dropbid.shared.events.AuctionClosedEvent;
import com.dropbid.shared.streaming.ResilientStreamConsumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class AuctionClosedConsumer extends ResilientStreamConsumer {

    private final BidService bidService;
    private final ObjectMapper mapper;

    public AuctionClosedConsumer(StringRedisTemplate redis, BidService bidService, ObjectMapper mapper) {
        super(redis);
        this.bidService = bidService;
        this.mapper = mapper;
    }

    @Override protected String stream() { return "auction:closed"; }
    @Override protected String group() { return "bid-service"; }
    @Override protected String consumerName() { return "bid-auction-closed-consumer-1"; }

    @Override
    protected void handleMessage(MapRecord<String, Object, Object> record) {
        try {
            String json = (String) record.getValue().get("data");
            AuctionClosedEvent event = mapper.readValue(json, AuctionClosedEvent.class);
            bidService.markWon(event);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
