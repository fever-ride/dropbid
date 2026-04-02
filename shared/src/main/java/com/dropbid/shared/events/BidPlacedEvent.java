package com.dropbid.shared.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Published by the Auction Service to the {@code bid_placed} Redis Stream.
 * Consumed by: Bid Service (record history), Notification Service (push update).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BidPlacedEvent(
        String auctionId,
        String bidId,
        String itemId,
        String userId,
        long amount,
        long previousHighest,
        String previousBidder,
        String bidAcceptedAt,
        String timestamp
) {}
