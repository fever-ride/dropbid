package com.dropbid.bid.service;

import com.dropbid.bid.model.Bid;
import com.dropbid.bid.repository.BidRepository;
import com.dropbid.shared.events.AuctionClosedEvent;
import com.dropbid.shared.events.BidPlacedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.dropbid.shared.IdGenerator;

import java.util.List;

@Service
public class BidService {

    private static final Logger log = LoggerFactory.getLogger(BidService.class);

    private final BidRepository repo;

    public BidService(BidRepository repo) {
        this.repo = repo;
    }

    /**
     * Record a new bid from a BidPlacedEvent and mark any previous
     * ACTIVE bid by a different user in the same auction as OUTBID.
     */
    public void recordBid(BidPlacedEvent event) {
        // Always invalidate this bidder's own previous ACTIVE bids in this auction
        // (covers self-raise: bidder increases their own bid without being the floor)
        markOutbid(event.auctionId(), event.userId());

        // Mark the knocked-out bidder (only if it's a different person)
        if (event.previousBidder() != null && !event.previousBidder().isBlank()
                && !event.previousBidder().equals(event.userId())) {
            markOutbid(event.auctionId(), event.previousBidder());
        }

        Bid bid = new Bid();
        bid.setBidId(event.bidId() != null ? event.bidId() : IdGenerator.newId());
        bid.setAuctionId(event.auctionId());
        bid.setBidderId(event.userId());
        bid.setAmount(event.amount());
        bid.setStatus("ACTIVE");
        bid.setCreatedAt(event.bidAcceptedAt());
        repo.save(bid);

        log.debug("recorded bid {} auction={} bidder={} amount={}",
                bid.getBidId(), bid.getAuctionId(), bid.getBidderId(), bid.getAmount());
    }

    public List<Bid> getAuctionBids(String auctionId) {
        return repo.findByAuctionId(auctionId);
    }

    public List<Bid> getUserBids(String userId) {
        return repo.findByBidderId(userId);
    }

    /**
     * Mark the winning bids as WON when an auction closes.
     * Loads all bids for the auction once, then filters by winner IDs.
     */
    public void markWon(AuctionClosedEvent event) {
        if (event.winners() == null || event.winners().isEmpty()) return;

        repo.findByAuctionId(event.auctionId()).stream()
                .filter(b -> "ACTIVE".equals(b.getStatus())
                        && event.winners().containsKey(b.getBidderId()))
                .forEach(b -> {
                    b.setStatus("WON");
                    repo.update(b);
                    log.debug("marked bid {} WON auction={} bidder={}",
                            b.getBidId(), b.getAuctionId(), b.getBidderId());
                });
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void markOutbid(String auctionId, String previousBidder) {
        repo.findByAuctionId(auctionId).stream()
                .filter(b -> b.getBidderId().equals(previousBidder) && "ACTIVE".equals(b.getStatus()))
                .forEach(b -> {
                    b.setStatus("OUTBID");
                    repo.update(b);
                });
    }
}
