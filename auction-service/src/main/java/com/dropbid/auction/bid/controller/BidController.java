package com.dropbid.auction.bid.controller;

import com.dropbid.auction.bid.model.Bid;
import com.dropbid.auction.bid.repository.BidStore;
import com.dropbid.auction.model.Auction;
import com.dropbid.auction.repository.AuctionStore;
import com.dropbid.shared.security.UserPrincipal;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
public class BidController {

    private final BidStore            bidStore;
    private final AuctionStore        auctionStore;
    private final StringRedisTemplate redis;

    public BidController(BidStore bidStore, AuctionStore auctionStore, StringRedisTemplate redis) {
        this.bidStore     = bidStore;
        this.auctionStore = auctionStore;
        this.redis        = redis;
    }

    /**
     * GET /auctions/{auctionId}/bids
     * Full bid history for one auction, with status derived from the live winners set.
     */
    @GetMapping("/auctions/{auctionId}/bids")
    @PreAuthorize("isAuthenticated()")
    public List<BidResponse> getAuctionBids(@PathVariable String auctionId) {
        List<Bid> bids = bidStore.findByAuctionId(auctionId);
        Map<String, Long> winners = resolveWinners(auctionId);
        return bids.stream()
                .map(b -> toResponse(b, winners))
                .toList();
    }

    /** GET /bids/me — caller's own bid history across all auctions */
    @GetMapping("/bids/me")
    @PreAuthorize("isAuthenticated()")
    public List<BidResponse> getMyBids(@AuthenticationPrincipal UserPrincipal principal) {
        return enrichWithStatus(bidStore.findByBidderId(principal.userId()));
    }

    /** GET /bids/user/{userId} — any user's bid history (admin only) */
    @GetMapping("/bids/user/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public List<BidResponse> getUserBids(@PathVariable String userId) {
        return enrichWithStatus(bidStore.findByBidderId(userId));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Enrich a list of bids (potentially spanning multiple auctions) with derived status.
     * Winners are resolved once per distinct auctionId to avoid redundant lookups.
     */
    private List<BidResponse> enrichWithStatus(List<Bid> bids) {
        Map<String, Map<String, Long>> winnersCache = new HashMap<>();
        return bids.stream()
                .map(b -> toResponse(b,
                        winnersCache.computeIfAbsent(b.getAuctionId(), this::resolveWinners)))
                .toList();
    }

    /**
     * Determine the current winners for an auction:
     *  1. Try the live Redis ZSET — present when the auction is still OPEN.
     *  2. Fall back to Auction.winners in DynamoDB — populated on every bid,
     *     and is the authoritative record after the auction is CLOSED.
     */
    private Map<String, Long> resolveWinners(String auctionId) {
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redis.opsForZSet().rangeWithScores("auction:" + auctionId + ":winners", 0, -1);
        if (tuples != null && !tuples.isEmpty()) {
            Map<String, Long> winners = new HashMap<>();
            for (ZSetOperations.TypedTuple<String> t : tuples) {
                if (t.getValue() != null && t.getScore() != null) {
                    winners.put(t.getValue(), t.getScore().longValue());
                }
            }
            return winners;
        }
        // Auction is closed or Redis key was evicted — fall back to DynamoDB snapshot
        Auction auction = auctionStore.findByIdOrNull(auctionId);
        if (auction != null && auction.getWinners() != null) {
            return auction.getWinners();
        }
        return Map.of();
    }

    /**
     * A bid is WINNING iff the winners map contains exactly this (bidderId, amount) pair —
     * meaning this is the bid that currently holds the bidder's seat in the winners set.
     * Earlier bids by the same bidder (lower self-raise amounts) are OUTBID.
     */
    private String computeStatus(Bid bid, Map<String, Long> winners) {
        Long winnerAmount = winners.get(bid.getBidderId());
        return (winnerAmount != null && winnerAmount.equals(bid.getAmount()))
                ? "WINNING" : "OUTBID";
    }

    private BidResponse toResponse(Bid bid, Map<String, Long> winners) {
        return new BidResponse(
                bid.getBidId(), bid.getAuctionId(), bid.getBidderId(),
                bid.getAmount(), computeStatus(bid, winners), bid.getCreatedAt()
        );
    }
}
