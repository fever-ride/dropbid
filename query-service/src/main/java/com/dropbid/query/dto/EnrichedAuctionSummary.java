package com.dropbid.query.dto;

import com.dropbid.query.model.Auction;
import com.dropbid.query.model.ItemLookup;

import java.time.Instant;

public record EnrichedAuctionSummary(
        String  auctionId,
        String  itemId,
        String  itemTitle,
        String  itemImageUrl,
        String  itemSeries,
        String  shopId,
        String  sellerId,
        String  status,
        long    startingBid,
        long    currentHighest,
        long    bidCount,
        String  endTime,
        Long    quantity,
        Instant closedAt,
        Instant updatedAt
) {
    public static EnrichedAuctionSummary from(Auction a, ItemLookup item) {
        return new EnrichedAuctionSummary(
                a.getAuctionId(),
                a.getItemId(),
                item != null ? item.getTitle()    : null,
                item != null ? item.getImageUrl() : null,
                item != null ? item.getSeries()   : null,
                a.getShopId(),
                a.getSellerId(),
                a.getStatus(),
                a.getStartingBid(),
                a.getCurrentHighest(),
                a.getBidCount(),
                a.getEndTime(),
                a.getQuantity(),
                a.getClosedAt(),
                a.getUpdatedAt()
        );
    }
}
