package com.dropbid.shop.dto;

import com.dropbid.shop.model.CollectibleItem;

import java.time.Instant;

public record ItemResponse(
        String id,
        String shopId,
        String title,
        String description,
        String series,
        String edition,
        String condition,
        long originalRetailPrice,
        long estimatedMarketValue,
        String imageUrl,
        Instant createdAt
) {
    public static ItemResponse from(CollectibleItem i) {
        return new ItemResponse(
                i.getId(), i.getShopId(), i.getTitle(), i.getDescription(),
                i.getSeries(), i.getEdition(), i.getCondition().name(),
                i.getOriginalRetailPrice(), i.getEstimatedMarketValue(),
                i.getImageUrl(), i.getCreatedAt()
        );
    }
}
