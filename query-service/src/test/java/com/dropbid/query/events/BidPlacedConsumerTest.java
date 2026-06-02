package com.dropbid.query.events;

import com.dropbid.query.model.Auction;
import com.dropbid.query.model.Bid;
import com.dropbid.query.repository.AuctionRepository;
import com.dropbid.query.repository.BidRepository;
import com.dropbid.shared.events.BidPlacedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link BidPlacedConsumer#handleBidPlaced}.
 *
 * <p>The consumer is instantiated directly to avoid Spring context overhead and
 * the {@code @PostConstruct init()} Redis connection that
 * {@link com.dropbid.shared.streaming.ResilientStreamConsumer} would otherwise
 * trigger.
 */
@ExtendWith(MockitoExtension.class)
class BidPlacedConsumerTest {

    @Mock AuctionRepository  auctionRepo;
    @Mock BidRepository      bidRepo;
    @Mock StringRedisTemplate redis;

    BidPlacedConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new BidPlacedConsumer(redis, auctionRepo, bidRepo, new ObjectMapper());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static BidPlacedEvent event(String bidId, String auctionId, long amount) {
        return new BidPlacedEvent(
                auctionId,
                bidId,
                "item-1",
                "seller-1",
                "bidder-1",
                amount,
                0L,
                null,
                Instant.now().toString(),
                Instant.now().toString()
        );
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    /**
     * Happy path: new bid inserts a Bid row and increments auction counters.
     */
    @Test
    void newBid_insertsBidAndIncrementsCounters() {
        when(bidRepo.existsById("bid-1")).thenReturn(false);
        when(auctionRepo.incrementBidCounters(eq("a1"), eq(300L), any())).thenReturn(1);
        when(bidRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        consumer.handleBidPlaced(event("bid-1", "a1", 300));

        // Bid row saved with correct fields.
        ArgumentCaptor<Bid> bidCaptor = ArgumentCaptor.forClass(Bid.class);
        verify(bidRepo).save(bidCaptor.capture());
        Bid saved = bidCaptor.getValue();
        assertThat(saved.getBidId()).isEqualTo("bid-1");
        assertThat(saved.getAuctionId()).isEqualTo("a1");
        assertThat(saved.getBidderId()).isEqualTo("bidder-1");
        assertThat(saved.getItemId()).isEqualTo("item-1");
        assertThat(saved.getAmount()).isEqualTo(300);

        // Counter update called once.
        verify(auctionRepo).incrementBidCounters(eq("a1"), eq(300L), any());
        // No skeletal auction created when counter update succeeds.
        verify(auctionRepo, never()).save(any());
    }

    /**
     * Duplicate bid (PEL redelivery): bidId already exists — skip all writes.
     */
    @Test
    void duplicateBidId_skipsAllProcessing() {
        when(bidRepo.existsById("bid-dup")).thenReturn(true);

        consumer.handleBidPlaced(event("bid-dup", "a1", 100));

        verify(bidRepo, never()).save(any());
        verify(auctionRepo, never()).incrementBidCounters(any(), anyLong(), any());
        verify(auctionRepo, never()).save(any());
    }

    /**
     * Out-of-order delivery: bid arrives before auction:created.
     * incrementBidCounters returns 0 (no auction row exists) — a skeletal
     * Auction row must be created so the bid is not orphaned.
     */
    @Test
    void noAuctionRow_createsSkeletalAuction() {
        when(bidRepo.existsById("bid-2")).thenReturn(false);
        // 0 rows updated → auction row does not exist yet.
        when(auctionRepo.incrementBidCounters(eq("a2"), eq(150L), any())).thenReturn(0);
        when(bidRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(auctionRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        consumer.handleBidPlaced(event("bid-2", "a2", 150));

        // Skeletal auction row should be persisted.
        ArgumentCaptor<Auction> auctionCaptor = ArgumentCaptor.forClass(Auction.class);
        verify(auctionRepo).save(auctionCaptor.capture());

        Auction skeletal = auctionCaptor.getValue();
        assertThat(skeletal.getAuctionId()).isEqualTo("a2");
        assertThat(skeletal.getItemId()).isEqualTo("item-1");
        assertThat(skeletal.getStatus()).isEqualTo("OPEN");
        assertThat(skeletal.getCurrentHighest()).isEqualTo(150);
        assertThat(skeletal.getBidCount()).isEqualTo(1);
    }

    // ── handleMessage JSON path ───────────────────────────────────────────────

    @Test
    void handleMessage_parsesJsonAndInsertsBid() throws Exception {
        BidPlacedEvent event = new BidPlacedEvent(
                "a-hm", "bid-hm", "item-hm", "seller-1", "buyer-1",
                400L, 0L, null,
                java.time.Instant.now().toString(),
                java.time.Instant.now().toString());
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(event);

        java.util.Map<Object, Object> body = new java.util.HashMap<>();
        body.put("data", json);
        org.springframework.data.redis.connection.stream.MapRecord<String, Object, Object> record =
                org.springframework.data.redis.connection.stream.MapRecord.create("bid_placed", body);

        when(bidRepo.existsById("bid-hm")).thenReturn(false);
        when(auctionRepo.incrementBidCounters(eq("a-hm"), eq(400L), any())).thenReturn(1);
        when(bidRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        consumer.handleMessage(record);

        verify(bidRepo).save(argThat(b -> "bid-hm".equals(b.getBidId())));
    }
}
