package com.dropbid.query.dto;

import com.dropbid.query.model.AuctionSummary;
import com.dropbid.query.model.ItemLookup;

import java.time.Instant;

public record EnrichedAuctionSummary(
        String auctionId,
        String itemId,
        String itemTitle,
        String itemImageUrl,
        String itemSeries,
        String shopId,
        String sellerId,
        String status,
        long currentHighest,
        long bidCount,
        Instant closedAt,
        Instant updatedAt
) {
    public static EnrichedAuctionSummary from(AuctionSummary s, ItemLookup item) {
        return new EnrichedAuctionSummary(
                s.getAuctionId(),
                s.getItemId(),
                item != null ? item.getTitle() : null,
                item != null ? item.getImageUrl() : null,
                item != null ? item.getSeries() : null,
                s.getShopId(),
                s.getSellerId(),
                s.getStatus(),
                s.getCurrentHighest(),
                s.getBidCount(),
                s.getClosedAt(),
                s.getUpdatedAt()
        );
    }
}
