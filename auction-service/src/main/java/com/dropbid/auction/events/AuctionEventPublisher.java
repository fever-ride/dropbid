package com.dropbid.auction.events;

import com.dropbid.shared.events.AuctionClosedEvent;
import com.dropbid.shared.events.BidPlacedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Publishes auction domain events to Redis Streams.
 *
 *  bid_placed     → consumed by Bid Service + Notification Service
 *  auction:closed → consumed by Payment Service
 */
@Component
public class AuctionEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(AuctionEventPublisher.class);
    private static final String STREAM_BID_PLACED     = "bid_placed";
    private static final String STREAM_AUCTION_CLOSED = "auction:closed";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public AuctionEventPublisher(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis  = redis;
        this.mapper = mapper;
    }

    public void publishBidPlaced(BidPlacedEvent event) {
        publish(STREAM_BID_PLACED, event);
    }

    public void publishAuctionClosed(AuctionClosedEvent event) {
        publish(STREAM_AUCTION_CLOSED, event);
    }

    private void publish(String stream, Object event) {
        try {
            String json = mapper.writeValueAsString(event);
            RecordId id = redis.opsForStream().add(
                    StreamRecords.newRecord()
                            .ofMap(Map.of("data", json))
                            .withStreamKey(stream)
            );
            log.debug("published to stream={} id={}", stream, id);
        } catch (JsonProcessingException e) {
            log.error("failed to serialize event for stream {}: {}", stream, e.getMessage());
        }
    }
}
