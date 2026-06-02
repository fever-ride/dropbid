package com.dropbid.query.events;

import com.dropbid.query.model.Auction;
import com.dropbid.query.model.AuctionWinner;
import com.dropbid.query.repository.AuctionRepository;
import com.dropbid.query.repository.AuctionWinnerRepository;
import com.dropbid.shared.events.AuctionClosedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AuctionClosedConsumer#handleAuctionClosed}.
 *
 * <p>The consumer is instantiated directly to avoid Spring context overhead.
 * {@code @PostConstruct init()} is never invoked, so no Redis connection is required.
 */
@ExtendWith(MockitoExtension.class)
class AuctionClosedConsumerTest {

    @Mock AuctionRepository       auctionRepo;
    @Mock AuctionWinnerRepository winnerRepo;
    @Mock StringRedisTemplate      redis;

    AuctionClosedConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new AuctionClosedConsumer(redis, auctionRepo, winnerRepo, new ObjectMapper());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static AuctionClosedEvent event(String auctionId, Map<String, Long> winners) {
        return new AuctionClosedEvent(
                auctionId,
                winners,
                "item-1",
                "shop-1",
                Instant.now().toString()
        );
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    /**
     * Happy path: existing auction marked CLOSED and winner rows persisted for each
     * entry in the winners map.
     */
    @Test
    void existingAuction_markedClosedAndWinnersWritten() {
        Auction existing = new Auction();
        existing.setAuctionId("a1");
        existing.setStatus("OPEN");

        when(auctionRepo.findById("a1")).thenReturn(Optional.of(existing));
        when(auctionRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(winnerRepo.existsByAuctionIdAndBidderId(eq("a1"), any())).thenReturn(false);
        when(winnerRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        consumer.handleAuctionClosed(event("a1", Map.of("bidder-A", 500L, "bidder-B", 300L)));

        // Auction row saved with CLOSED status.
        ArgumentCaptor<Auction> auctionCaptor = ArgumentCaptor.forClass(Auction.class);
        verify(auctionRepo).save(auctionCaptor.capture());
        assertThat(auctionCaptor.getValue().getStatus()).isEqualTo("CLOSED");
        assertThat(auctionCaptor.getValue().getClosedAt()).isNotNull();

        // One AuctionWinner row per winner.
        ArgumentCaptor<AuctionWinner> winnerCaptor = ArgumentCaptor.forClass(AuctionWinner.class);
        verify(winnerRepo, times(2)).save(winnerCaptor.capture());

        List<AuctionWinner> saved = winnerCaptor.getAllValues();
        assertThat(saved).extracting(AuctionWinner::getAuctionId)
                .containsOnly("a1");
        assertThat(saved).extracting(AuctionWinner::getBidderId)
                .containsExactlyInAnyOrder("bidder-A", "bidder-B");
        assertThat(saved).extracting(AuctionWinner::getAmount)
                .containsExactlyInAnyOrder(500L, 300L);
    }

    /**
     * No-bid auction: empty winners map — auction row is still marked CLOSED
     * but no winner rows are written.
     */
    @Test
    void nullWinners_auctionClosedButNoWinnerRowsWritten() {
        Auction existing = new Auction();
        existing.setAuctionId("a2");
        existing.setStatus("OPEN");

        when(auctionRepo.findById("a2")).thenReturn(Optional.of(existing));
        when(auctionRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        consumer.handleAuctionClosed(event("a2", null));

        verify(auctionRepo).save(any());
        verify(winnerRepo, never()).save(any());
    }

    /**
     * Redelivery: winner row already exists (existsBy returns true).
     * The consumer must skip the save to maintain idempotency.
     */
    @Test
    void existingWinner_skippedOnRedelivery() {
        Auction existing = new Auction();
        existing.setAuctionId("a3");
        existing.setStatus("OPEN");

        when(auctionRepo.findById("a3")).thenReturn(Optional.of(existing));
        when(auctionRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        // Winner already persisted on a previous delivery.
        when(winnerRepo.existsByAuctionIdAndBidderId("a3", "bidder-X")).thenReturn(true);

        consumer.handleAuctionClosed(event("a3", Map.of("bidder-X", 400L)));

        // Auction still marked CLOSED.
        verify(auctionRepo).save(any());
        // Winner row not saved again.
        verify(winnerRepo, never()).save(any());
    }

    /**
     * Out-of-order delivery: auction:closed arrives before auction:created.
     * A skeletal auction row must be created so the close can be recorded.
     */
    @Test
    void auctionNotFound_createsSkeletalAuctionAndWritesWinners() {
        when(auctionRepo.findById("a4")).thenReturn(Optional.empty());
        when(auctionRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(winnerRepo.existsByAuctionIdAndBidderId("a4", "bidder-Y")).thenReturn(false);
        when(winnerRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        consumer.handleAuctionClosed(event("a4", Map.of("bidder-Y", 600L)));

        // Skeletal auction row is saved with CLOSED status and correct IDs.
        ArgumentCaptor<Auction> auctionCaptor = ArgumentCaptor.forClass(Auction.class);
        verify(auctionRepo).save(auctionCaptor.capture());
        Auction skeletal = auctionCaptor.getValue();
        assertThat(skeletal.getAuctionId()).isEqualTo("a4");
        assertThat(skeletal.getItemId()).isEqualTo("item-1");
        assertThat(skeletal.getShopId()).isEqualTo("shop-1");
        assertThat(skeletal.getStatus()).isEqualTo("CLOSED");

        // Winner row still created.
        verify(winnerRepo, times(1)).save(any());
    }

    // ── handleMessage JSON path ───────────────────────────────────────────────

    @Test
    void handleMessage_parsesJsonAndClosesAuction() throws Exception {
        com.dropbid.shared.events.AuctionClosedEvent event =
                new com.dropbid.shared.events.AuctionClosedEvent(
                        "a-hm-2", java.util.Map.of("buyer-hm", 300L),
                        "item-hm", "shop-hm",
                        java.time.Instant.now().toString());
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(event);

        java.util.Map<Object, Object> body = new java.util.HashMap<>();
        body.put("data", json);
        org.springframework.data.redis.connection.stream.MapRecord<String, Object, Object> record =
                org.springframework.data.redis.connection.stream.MapRecord.create("auction:closed", body);

        com.dropbid.query.model.Auction existing = new com.dropbid.query.model.Auction();
        existing.setAuctionId("a-hm-2");
        when(auctionRepo.findById("a-hm-2")).thenReturn(java.util.Optional.of(existing));
        when(auctionRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(winnerRepo.existsByAuctionIdAndBidderId("a-hm-2", "buyer-hm")).thenReturn(false);
        when(winnerRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        consumer.handleMessage(record);

        verify(auctionRepo).save(argThat(a -> "CLOSED".equals(a.getStatus())));
        verify(winnerRepo).save(any());
    }
}
