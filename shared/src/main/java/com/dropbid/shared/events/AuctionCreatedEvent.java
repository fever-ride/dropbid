package com.dropbid.shared.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Published by the Auction Service to the {@code auction:created} Redis Stream
 * immediately after a new auction is persisted to DynamoDB.
 *
 * Consumed by: Query Service (bootstrap the auction_summary row with structural
 * fields that bid_placed events do not carry: endTime, quantity, startingBid).
 *
 * status is either "OPEN" (no startTime or startTime already passed)
 * or "PENDING" (startTime is in the future).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AuctionCreatedEvent(
        String auctionId,
        String itemId,
        String shopId,
        String sellerId,
        long   startingBid,
        String status,
        String startTime,
        String endTime,
        long   quantity
) {}
