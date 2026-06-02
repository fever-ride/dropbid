package com.dropbid.auction.events;

import com.dropbid.shared.events.AuctionClosedEvent;
import com.dropbid.shared.events.AuctionCreatedEvent;
import com.dropbid.shared.events.BidPlacedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuctionEventPublisher}.
 *
 * Verifies that each publish method writes to the correct Redis Stream key
 * and that the payload contains valid JSON with the expected fields.
 */
@ExtendWith(MockitoExtension.class)
class AuctionEventPublisherTest {

    @Mock StringRedisTemplate redis;
    @SuppressWarnings("rawtypes")
    @Mock StreamOperations streamOps;

    ObjectMapper mapper = new ObjectMapper();
    AuctionEventPublisher publisher;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        when(redis.opsForStream()).thenReturn(streamOps);
        when(streamOps.add(any())).thenReturn(RecordId.autoGenerate());
        publisher = new AuctionEventPublisher(redis, mapper);
    }

    @SuppressWarnings("unchecked")
    @Test
    void publishBidPlaced_writesToBidPlacedStream() {
        BidPlacedEvent event = new BidPlacedEvent(
                "auction-1", "bid-1", "item-1", "seller-1",
                "buyer-1", 500L, 0L, null,
                Instant.now().toString(), Instant.now().toString());

        publisher.publishBidPlaced(event);

        ArgumentCaptor<MapRecord> captor = ArgumentCaptor.forClass(MapRecord.class);
        verify(streamOps).add(captor.capture());

        MapRecord<String, String, String> record = captor.getValue();
        assertThat(record.getStream()).isEqualTo("bid_placed");
        assertThat(record.getValue().get("data").toString()).contains("auction-1");
        assertThat(record.getValue().get("data").toString()).contains("bid-1");
    }

    @SuppressWarnings("unchecked")
    @Test
    void publishAuctionCreated_writesToAuctionCreatedStream() {
        AuctionCreatedEvent event = new AuctionCreatedEvent(
                "auction-2", "item-2", "shop-1", "seller-1",
                100L, "OPEN", null, "2099-01-01T00:00:00Z", 1L);

        publisher.publishAuctionCreated(event);

        ArgumentCaptor<MapRecord> captor = ArgumentCaptor.forClass(MapRecord.class);
        verify(streamOps).add(captor.capture());

        MapRecord<String, String, String> record = captor.getValue();
        assertThat(record.getStream()).isEqualTo("auction:created");
        String json = record.getValue().get("data").toString();
        assertThat(json).contains("auction-2");
        assertThat(json).contains("OPEN");
    }

    @SuppressWarnings("unchecked")
    @Test
    void publishAuctionClosed_writesToAuctionClosedStream() {
        AuctionClosedEvent event = new AuctionClosedEvent(
                "auction-3", Map.of("buyer-1", 500L),
                "item-3", "shop-1", Instant.now().toString());

        publisher.publishAuctionClosed(event);

        ArgumentCaptor<MapRecord> captor = ArgumentCaptor.forClass(MapRecord.class);
        verify(streamOps).add(captor.capture());

        MapRecord<String, String, String> record = captor.getValue();
        assertThat(record.getStream()).isEqualTo("auction:closed");
        String json = record.getValue().get("data").toString();
        assertThat(json).contains("auction-3");
        assertThat(json).contains("buyer-1");
    }
}
