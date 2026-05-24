package com.dropbid.query.dto;

import java.time.Instant;

/**
 * Spring Data projection interface for native queries that aggregate the
 * {@code bid} table grouped by {@code (auctionId, bidderId)}.
 *
 * <p>Used by both the buyer dashboard (one row per auction the buyer bid on)
 * and the seller bids view (one row per bidder on a given auction).
 *
 * <p>The computed {@code bidStatus} is derived in SQL:
 * <ul>
 *   <li>{@code WON}    — a row exists in {@code auction_winner} for this pair</li>
 *   <li>{@code OUTBID} — auction is CLOSED and no winner row found</li>
 *   <li>{@code ACTIVE} — auction is still OPEN</li>
 * </ul>
 */
public interface BidSummaryProjection {
    String  getAuctionId();
    String  getBidderId();
    String  getItemId();
    long    getLatestAmount();
    long    getBidCount();
    Instant getFirstBidAt();
    Instant getLastBidAt();
    String  getAuctionStatus();
    String  getBidStatus();
    String  getPaymentStatus();
    String  getPaymentId();
}
