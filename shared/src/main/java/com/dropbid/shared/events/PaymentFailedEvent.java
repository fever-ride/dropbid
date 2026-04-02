package com.dropbid.shared.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Published by the Payment Service to the {@code payment:failed} Redis Stream.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentFailedEvent(
        String paymentId,
        String auctionId,
        String userId,
        String reason,
        long amount,
        String failedAt
) {}
