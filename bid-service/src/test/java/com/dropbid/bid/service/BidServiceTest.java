package com.dropbid.bid.service;

import com.dropbid.bid.model.Bid;
import com.dropbid.bid.repository.BidRepository;
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

    private FakeBidRepository repo;
    private BidService service;

    @BeforeEach
    void setUp() {
        repo = new FakeBidRepository();
        service = new BidService(repo);
    }

    // ── recordBid ───────────────────────────────────────────────────────────

    @Test
    void recordBid_savesNewBidAsActive() {
        BidPlacedEvent event = bidEvent("auction1", "bid1", "buyerA", 500, null);
        service.recordBid(event);

        List<Bid> bids = repo.findByAuctionId("auction1");
        assertEquals(1, bids.size());
        assertEquals("ACTIVE", bids.get(0).getStatus());
        assertEquals(500L, bids.get(0).getAmount());
        assertEquals("buyerA", bids.get(0).getBidderId());
    }

    @Test
    void recordBid_marksKnockedOutBidderAsOutbid() {
        service.recordBid(bidEvent("auction1", "bid1", "buyerA", 500, null));
        service.recordBid(bidEvent("auction1", "bid2", "buyerB", 600, "buyerA"));

        Bid bidA = repo.findById("bid1");
        Bid bidB = repo.findById("bid2");

        assertEquals("OUTBID", bidA.getStatus());
        assertEquals("ACTIVE", bidB.getStatus());
    }

    @Test
    void recordBid_selfRaise_marksPreviousOwnBidAsOutbid() {
        service.recordBid(bidEvent("auction1", "bid1", "buyerA", 500, null));
        service.recordBid(bidEvent("auction1", "bid2", "buyerA", 700, null));

        Bid old = repo.findById("bid1");
        Bid raised = repo.findById("bid2");

        assertEquals("OUTBID", old.getStatus());
        assertEquals("ACTIVE", raised.getStatus());
    }

    @Test
    void recordBid_doesNotOutbidSameUserTwice() {
        service.recordBid(bidEvent("auction1", "bid1", "buyerA", 500, null));
        // buyerB knocks out buyerA, and previousBidder == buyerA
        service.recordBid(bidEvent("auction1", "bid2", "buyerB", 600, "buyerA"));
        // buyerA is already OUTBID; buyerC knocks out buyerB
        service.recordBid(bidEvent("auction1", "bid3", "buyerC", 700, "buyerB"));

        assertEquals("OUTBID", repo.findById("bid1").getStatus());
        assertEquals("OUTBID", repo.findById("bid2").getStatus());
        assertEquals("ACTIVE", repo.findById("bid3").getStatus());
    }

    @Test
    void recordBid_previousBidderBlank_noOutbid() {
        service.recordBid(bidEvent("auction1", "bid1", "buyerA", 500, ""));
        assertEquals("ACTIVE", repo.findById("bid1").getStatus());
    }

    @Test
    void recordBid_multipleAuctions_areIsolated() {
        service.recordBid(bidEvent("auction1", "bid1", "buyerA", 500, null));
        service.recordBid(bidEvent("auction2", "bid2", "buyerA", 300, null));

        assertEquals("ACTIVE", repo.findById("bid1").getStatus());
        assertEquals("ACTIVE", repo.findById("bid2").getStatus());
    }

    // ── markWon ─────────────────────────────────────────────────────────────

    @Test
    void markWon_marksWinnersAsWon() {
        service.recordBid(bidEvent("auction1", "bid1", "buyerA", 500, null));
        service.recordBid(bidEvent("auction1", "bid2", "buyerB", 600, "buyerA"));
        service.recordBid(bidEvent("auction1", "bid3", "buyerC", 700, "buyerB"));

        AuctionClosedEvent closeEvent = new AuctionClosedEvent(
                "auction1", Map.of("buyerC", 700L), "item1", "shop1", Instant.now().toString());
        service.markWon(closeEvent);

        assertEquals("OUTBID", repo.findById("bid1").getStatus());
        assertEquals("OUTBID", repo.findById("bid2").getStatus());
        assertEquals("WON", repo.findById("bid3").getStatus());
    }

    @Test
    void markWon_multipleWinners() {
        service.recordBid(bidEvent("auction1", "bid1", "buyerA", 500, null));
        service.recordBid(bidEvent("auction1", "bid2", "buyerB", 600, null));
        service.recordBid(bidEvent("auction1", "bid3", "buyerC", 700, "buyerA"));

        AuctionClosedEvent closeEvent = new AuctionClosedEvent(
                "auction1", Map.of("buyerB", 600L, "buyerC", 700L),
                "item1", "shop1", Instant.now().toString());
        service.markWon(closeEvent);

        assertEquals("OUTBID", repo.findById("bid1").getStatus());
        assertEquals("WON", repo.findById("bid2").getStatus());
        assertEquals("WON", repo.findById("bid3").getStatus());
    }

    @Test
    void markWon_emptyWinners_noChange() {
        service.recordBid(bidEvent("auction1", "bid1", "buyerA", 500, null));

        AuctionClosedEvent closeEvent = new AuctionClosedEvent(
                "auction1", Map.of(), "item1", "shop1", Instant.now().toString());
        service.markWon(closeEvent);

        assertEquals("ACTIVE", repo.findById("bid1").getStatus());
    }

    @Test
    void markWon_nullWinners_noChange() {
        service.recordBid(bidEvent("auction1", "bid1", "buyerA", 500, null));

        AuctionClosedEvent closeEvent = new AuctionClosedEvent(
                "auction1", null, "item1", "shop1", Instant.now().toString());
        service.markWon(closeEvent);

        assertEquals("ACTIVE", repo.findById("bid1").getStatus());
    }

    @Test
    void markWon_onlyMarksActiveBids_notAlreadyOutbid() {
        service.recordBid(bidEvent("auction1", "bid1", "buyerA", 500, null));
        service.recordBid(bidEvent("auction1", "bid2", "buyerA", 700, null));
        // bid1 is OUTBID (self-raise), bid2 is ACTIVE

        AuctionClosedEvent closeEvent = new AuctionClosedEvent(
                "auction1", Map.of("buyerA", 700L), "item1", "shop1", Instant.now().toString());
        service.markWon(closeEvent);

        assertEquals("OUTBID", repo.findById("bid1").getStatus());
        assertEquals("WON", repo.findById("bid2").getStatus());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private BidPlacedEvent bidEvent(String auctionId, String bidId, String userId,
                                    long amount, String previousBidder) {
        return new BidPlacedEvent(auctionId, bidId, "item1", "seller1", userId,
                amount, 0L, previousBidder, Instant.now().toString(), Instant.now().toString());
    }

    /**
     * In-memory fake that replaces the DynamoDB-backed BidRepository.
     * Stores bids in a list and supports the same query patterns.
     */
    static class FakeBidRepository extends BidRepository {

        private final List<Bid> store = new ArrayList<>();

        FakeBidRepository() {
            super(null); // no DynamoDB client needed
        }

        @Override
        public void save(Bid bid) {
            store.add(bid);
        }

        @Override
        public Bid findById(String bidId) {
            return store.stream().filter(b -> b.getBidId().equals(bidId)).findFirst().orElse(null);
        }

        @Override
        public List<Bid> findByAuctionId(String auctionId) {
            return store.stream().filter(b -> b.getAuctionId().equals(auctionId)).toList();
        }

        @Override
        public List<Bid> findByBidderId(String bidderId) {
            return store.stream().filter(b -> b.getBidderId().equals(bidderId)).toList();
        }

        @Override
        public void update(Bid bid) {
            // in-memory — bid object is already mutated
        }
    }
}
