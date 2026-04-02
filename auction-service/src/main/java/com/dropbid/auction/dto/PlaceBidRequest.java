package com.dropbid.auction.dto;

import jakarta.validation.constraints.Min;

public record PlaceBidRequest(@Min(1) long amount) {}
