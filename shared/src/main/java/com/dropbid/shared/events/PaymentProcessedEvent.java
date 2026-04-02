package com.dropbid.shared.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Published by the Payment Service to the {@code payment:processed} Redis Stream.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentProcessedEvent(
        String paymentId,
        String auctionId,
        String userId,
        long amount,
        String processedAt
) {}
