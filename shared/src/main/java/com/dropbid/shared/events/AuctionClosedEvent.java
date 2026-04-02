package com.dropbid.shared.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * Published by the Auction Service to the {@code auction:closed} Redis Stream.
 * Consumed by: Payment Service (initiate charge per winner).
 *
 * winners: bidderId → winning bid amount
 *          single-winner auctions have exactly one entry
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AuctionClosedEvent(
        String auctionId,
        Map<String, Long> winners,
        String itemId,
        String shopId,
        String closedAt
) {}
