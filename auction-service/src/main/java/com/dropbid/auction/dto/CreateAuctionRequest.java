package com.dropbid.auction.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

public record CreateAuctionRequest(
        @NotBlank String itemId,
        @NotBlank String shopId,
        @Min(1) long startingBid,
        @Min(1) Long maxPrice,     // ceiling — must exceed startingBid
        String startTime,          // ISO-8601 — optional; null means start immediately
        @NotBlank String endTime,  // ISO-8601 instant, e.g. "2025-12-31T23:59:59Z"
        @Min(1) Long quantity      // number of winners; null defaults to 1
) {}
