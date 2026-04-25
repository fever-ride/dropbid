package com.dropbid.bid.service;

import com.dropbid.bid.model.Bid;
import com.dropbid.bid.repository.BidStore;
import com.dropbid.shared.events.AuctionClosedEvent;
import com.dropbid.shared.events.BidPlacedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BidServiceTest {

    private List<Bid> store;
    private BidService service;

    @BeforeEach
    void setUp() {
        store = new ArrayList<>();

        BidStore repo = new BidStore() {
            @Override public void save(Bid bid) { store.add(bid); }
            @Override public void update(Bid bid) { /* in-place mutation is enough */ }
            @Override public Bid findById(String bidId) {
                return store.stream().filter(b -> b.getBidId().equals(bidId)).findFirst().orElse(null);
            }
            @Override public List<Bid> findByAuctionId(String auctionId) {
                return store.stream().filter(b -> b.getAuctionId().equals(auctionId)).toList();
            }
            @Override public List<Bid> findByBidderId(String bidderId) {
                return store.stream().filter(b -> b.getBidderId().equals(bidderId)).toList();
            }
        };

        service = new BidService(repo);
    }

    private Bid findById(String bidId) {
        return store.stream().filter(b -> b.getBidId().equals(bidId)).findFirst().orElse(null);
    }

    // ── recordBid ───────────────────────────────────────────────────────────

    @Test
    void recordBid_savesNewBidAsActive() {
        service.recordBid(bidEvent("auction1", "bid1", "buyerA", 500, null));

        assertEquals(1, store.size());
        assertEquals("ACTIVE", store.get(0).getStatus());
        assertEquals(500L, store.get(0).getAmount());
        assertEquals("buyerA", store.get(0).getBidderId());
    }

    @Test
    void recordBid_marksKnockedOutBidderAsOutbid() {
        service.recordBid(bidEvent("auction1", "bid1", "buyerA", 500, null));
        service.recordBid(bidEvent("auction1", "bid2", "buyerB", 600, "buyerA"));

        assertEquals("OUTBID", findById("bid1").getStatus());
        assertEquals("ACTIVE", findById("bid2").getStatus());
    }

    @Test
    void recordBid_selfRaise_marksPreviousOwnBidAsOutbid() {
        service.recordBid(bidEvent("auction1", "bid1", "buyerA", 500, null));
        service.recordBid(bidEvent("auction1", "bid2", "buyerA", 700, null));

        assertEquals("OUTBID", findById("bid1").getStatus());
        assertEquals("ACTIVE", findById("bid2").getStatus());
    }

    @Test
    void recordBid_doesNotOutbidSameUserTwice() {
        service.recordBid(bidEvent("auction1", "bid1", "buyerA", 500, null));
        service.recordBid(bidEvent("auction1", "bid2", "buyerB", 600, "buyerA"));
        service.recordBid(bidEvent("auction1", "bid3", "buyerC", 700, "buyerB"));

        assertEquals("OUTBID", findById("bid1").getStatus());
        assertEquals("OUTBID", findById("bid2").getStatus());
        assertEquals("ACTIVE", findById("bid3").getStatus());
    }

    @Test
    void recordBid_previousBidderBlank_noOutbid() {
        service.recordBid(bidEvent("auction1", "bid1", "buyerA", 500, ""));
        assertEquals("ACTIVE", findById("bid1").getStatus());
    }

    @Test
    void recordBid_multipleAuctions_areIsolated() {
        service.recordBid(bidEvent("auction1", "bid1", "buyerA", 500, null));
        service.recordBid(bidEvent("auction2", "bid2", "buyerA", 300, null));

        assertEquals("ACTIVE", findById("bid1").getStatus());
        assertEquals("ACTIVE", findById("bid2").getStatus());
    }

    // ── markWon ─────────────────────────────────────────────────────────────

    @Test
    void markWon_marksWinnersAsWon() {
        service.recordBid(bidEvent("auction1", "bid1", "buyerA", 500, null));
        service.recordBid(bidEvent("auction1", "bid2", "buyerB", 600, "buyerA"));
        service.recordBid(bidEvent("auction1", "bid3", "buyerC", 700, "buyerB"));

        service.markWon(new AuctionClosedEvent(
                "auction1", Map.of("buyerC", 700L), "item1", "shop1", Instant.now().toString()));

        assertEquals("OUTBID", findById("bid1").getStatus());
        assertEquals("OUTBID", findById("bid2").getStatus());
        assertEquals("WON", findById("bid3").getStatus());
    }

    @Test
    void markWon_multipleWinners() {
        service.recordBid(bidEvent("auction1", "bid1", "buyerA", 500, null));
        service.recordBid(bidEvent("auction1", "bid2", "buyerB", 600, null));
        service.recordBid(bidEvent("auction1", "bid3", "buyerC", 700, "buyerA"));

        service.markWon(new AuctionClosedEvent(
                "auction1", Map.of("buyerB", 600L, "buyerC", 700L),
                "item1", "shop1", Instant.now().toString()));

        assertEquals("OUTBID", findById("bid1").getStatus());
        assertEquals("WON", findById("bid2").getStatus());
        assertEquals("WON", findById("bid3").getStatus());
    }

    @Test
    void markWon_emptyWinners_noChange() {
        service.recordBid(bidEvent("auction1", "bid1", "buyerA", 500, null));

        service.markWon(new AuctionClosedEvent(
                "auction1", Map.of(), "item1", "shop1", Instant.now().toString()));

        assertEquals("ACTIVE", findById("bid1").getStatus());
    }

    @Test
    void markWon_nullWinners_noChange() {
        service.recordBid(bidEvent("auction1", "bid1", "buyerA", 500, null));

        service.markWon(new AuctionClosedEvent(
                "auction1", null, "item1", "shop1", Instant.now().toString()));

        assertEquals("ACTIVE", findById("bid1").getStatus());
    }

    @Test
    void markWon_onlyMarksActiveBids_notAlreadyOutbid() {
        service.recordBid(bidEvent("auction1", "bid1", "buyerA", 500, null));
        service.recordBid(bidEvent("auction1", "bid2", "buyerA", 700, null));

        service.markWon(new AuctionClosedEvent(
                "auction1", Map.of("buyerA", 700L), "item1", "shop1", Instant.now().toString()));

        assertEquals("OUTBID", findById("bid1").getStatus());
        assertEquals("WON", findById("bid2").getStatus());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private BidPlacedEvent bidEvent(String auctionId, String bidId, String userId,
                                    long amount, String previousBidder) {
        return new BidPlacedEvent(auctionId, bidId, "item1", "seller1", userId,
                amount, 0L, previousBidder, Instant.now().toString(), Instant.now().toString());
    }
}
