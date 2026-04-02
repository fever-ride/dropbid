package com.dropbid.auction.concurrency;

import java.util.Map;

/**
 * Result returned by a bid strategy after a successful bid placement.
 *
 * currentWinners  : full snapshot of the winners sorted set at the moment the
 *                   bid was accepted (read inside the lock, guaranteed consistent)
 * newFloor        : minimum bid needed to enter the winners set after this bid
 * topBidder       : bidder with the highest amount in the winners set
 * previousBidder / previousHighest : winner knocked out of the set, if any
 */
public record BidResult(
        String auctionId,
        String bidderId,
        long   amount,
        long   newVersion,
        long   bidCount,
        String previousBidder,
        long   previousHighest,
        long   newFloor,
        String topBidder,
        Map<String, Long> currentWinners
) {}
