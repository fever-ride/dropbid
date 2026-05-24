package com.dropbid.auction.bid.controller;

/**
 * API response for a single bid record.
 *
 * {@code status} is derived at read time:
 *   WINNING — this is the bid that currently places the bidder in the winners set
 *             (i.e. bidderId is in winners AND this exact amount matches the ZSET score)
 *   OUTBID  — a higher bid exists for this bidder, or this bidder is no longer in winners
 */
public record BidResponse(
        String bidId,
        String auctionId,
        String bidderId,
        long   amount,
        String status,    // "WINNING" | "OUTBID" — never stored, always computed
        String createdAt
) {}
