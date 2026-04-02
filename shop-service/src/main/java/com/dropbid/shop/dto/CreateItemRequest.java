package com.dropbid.shop.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record CreateItemRequest(
        @NotBlank String title,
        String description,
        String series,
        String edition,
        @NotNull @Pattern(regexp = "NEW|LIKE_NEW|GOOD|FAIR") String condition,
        @Min(0) long originalRetailPrice,
        @Min(0) long estimatedMarketValue,
        String imageUrl
) {}
