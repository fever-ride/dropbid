package com.dropbid.query.events;

import com.dropbid.query.model.Auction;
import com.dropbid.query.repository.AuctionRepository;
import com.dropbid.shared.events.AuctionCreatedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.HashMap;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AuctionCreatedConsumer#handleAuctionCreated}.
 *
 * <p>No Spring context is loaded — the consumer is instantiated directly,
 * bypassing {@code @PostConstruct init()} which would require a live Redis
 * connection.  The {@code StringRedisTemplate} mock is passed to the parent
 * constructor but never called during these tests.
 */
@ExtendWith(MockitoExtension.class)
class AuctionCreatedConsumerTest {

    @Mock AuctionRepository  auctionRepo;
    @Mock StringRedisTemplate redis;

    AuctionCreatedConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new AuctionCreatedConsumer(redis, auctionRepo, new ObjectMapper());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static AuctionCreatedEvent event(String auctionId, String status, long startingBid) {
        return new AuctionCreatedEvent(
                auctionId,
                "item-1",
                "shop-1",
                "seller-1",
                startingBid,
                status,
                null,
                "2099-01-01T00:00:00Z",
                1L
        );
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    /**
     * Brand-new auction: a new row is created and all fields are set from the event.
     * currentHighest should be initialised to startingBid.
     */
    @Test
    void newAuction_createsRowWithAllFields() {
        when(auctionRepo.findById("a1")).thenReturn(Optional.empty());
        when(auctionRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        consumer.handleAuctionCreated(event("a1", "OPEN", 100));

        ArgumentCaptor<Auction> captor = ArgumentCaptor.forClass(Auction.class);
        verify(auctionRepo).save(captor.capture());

        Auction saved = captor.getValue();
        assertThat(saved.getAuctionId()).isEqualTo("a1");
        assertThat(saved.getItemId()).isEqualTo("item-1");
        assertThat(saved.getSellerId()).isEqualTo("seller-1");
        assertThat(saved.getStatus()).isEqualTo("OPEN");
        assertThat(saved.getStartingBid()).isEqualTo(100);
        assertThat(saved.getCurrentHighest()).isEqualTo(100); // initialised to startingBid
        assertThat(saved.getEndTime()).isEqualTo("2099-01-01T00:00:00Z");
        assertThat(saved.getQuantity()).isEqualTo(1L);
    }

    /**
     * PENDING auction created — status field should be PENDING on the saved row.
     */
    @Test
    void newAuction_pendingStatus_savedCorrectly() {
        when(auctionRepo.findById("a2")).thenReturn(Optional.empty());
        when(auctionRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        consumer.handleAuctionCreated(event("a2", "PENDING", 50));

        ArgumentCaptor<Auction> captor = ArgumentCaptor.forClass(Auction.class);
        verify(auctionRepo).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("PENDING");
    }

    /**
     * Existing OPEN auction (with bids already recorded): structural fields are
     * updated, but currentHighest must NOT be reset to startingBid because bids
     * have already raised it.
     */
    @Test
    void existingOpenAuction_updatesStructuralFields_doesNotResetCurrentHighest() {
        Auction existing = new Auction();
        existing.setAuctionId("a3");
        existing.setStatus("OPEN");
        existing.setCurrentHighest(500); // already has bids

        when(auctionRepo.findById("a3")).thenReturn(Optional.of(existing));
        when(auctionRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        consumer.handleAuctionCreated(event("a3", "OPEN", 100));

        ArgumentCaptor<Auction> captor = ArgumentCaptor.forClass(Auction.class);
        verify(auctionRepo).save(captor.capture());

        Auction saved = captor.getValue();
        assertThat(saved.getCurrentHighest()).isEqualTo(500); // unchanged
        assertThat(saved.getItemId()).isEqualTo("item-1");    // structural field updated
        assertThat(saved.getStartingBid()).isEqualTo(100);
    }

    /**
     * Auction already CLOSED (auction:closed arrived before auction:created).
     * Status must not be reverted to OPEN — doing so would break the read model.
     */
    @Test
    void closedAuction_statusNotOverwritten() {
        Auction existing = new Auction();
        existing.setAuctionId("a4");
        existing.setStatus("CLOSED");
        existing.setCurrentHighest(800);

        when(auctionRepo.findById("a4")).thenReturn(Optional.of(existing));
        when(auctionRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        consumer.handleAuctionCreated(event("a4", "OPEN", 100));

        ArgumentCaptor<Auction> captor = ArgumentCaptor.forClass(Auction.class);
        verify(auctionRepo).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("CLOSED"); // unchanged
    }

    /**
     * Brand-new row with startingBid=0 is unlikely in production, but ensure the
     * initialisation branch (currentHighest == 0) is taken and the value is set.
     */
    @Test
    void newAuction_currentHighestInitialisedToStartingBid() {
        when(auctionRepo.findById("a5")).thenReturn(Optional.empty());
        when(auctionRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        consumer.handleAuctionCreated(event("a5", "OPEN", 200));

        ArgumentCaptor<Auction> captor = ArgumentCaptor.forClass(Auction.class);
        verify(auctionRepo).save(captor.capture());
        assertThat(captor.getValue().getCurrentHighest()).isEqualTo(200);
    }

    // ── handleMessage JSON path ───────────────────────────────────────────────

    /**
     * Covers the full handleMessage → JSON parsing → handleAuctionCreated path.
     * This is how the consumer is invoked in production (via ResilientStreamConsumer).
     */
    @Test
    void handleMessage_parsesJsonAndCreatesRow() throws Exception {
        AuctionCreatedEvent event = new AuctionCreatedEvent(
                "a-hm-1", "item-x", "shop-x", "seller-x",
                200L, "OPEN", null, "2099-06-01T00:00:00Z", 3L);
        String json = new ObjectMapper().writeValueAsString(event);

        java.util.Map<Object, Object> body = new HashMap<>();
        body.put("data", json);
        MapRecord<String, Object, Object> record =
                MapRecord.create("auction:created", body);

        when(auctionRepo.findById("a-hm-1")).thenReturn(java.util.Optional.empty());
        when(auctionRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        consumer.handleMessage(record);

        verify(auctionRepo).save(argThat(a ->
                "a-hm-1".equals(a.getAuctionId())
                && "OPEN".equals(a.getStatus())
                && a.getQuantity() == 3L
        ));
    }
}
