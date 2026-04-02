package com.dropbid.shop.dto;

import com.dropbid.shop.model.SellerProfile;

import java.math.BigDecimal;
import java.time.Instant;

public record ShopResponse(
        String id,
        String ownerId,
        String name,
        String bio,
        BigDecimal rating,
        boolean verified,
        Instant createdAt
) {
    public static ShopResponse from(SellerProfile s) {
        return new ShopResponse(s.getId(), s.getOwnerId(), s.getName(), s.getBio(),
                s.getRating(), s.isVerified(), s.getCreatedAt());
    }
}
