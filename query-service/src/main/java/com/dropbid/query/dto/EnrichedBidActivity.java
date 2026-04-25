package com.dropbid.query.dto;

import com.dropbid.query.model.BidActivity;
import com.dropbid.query.model.ItemLookup;
import com.dropbid.query.model.UserLookup;

import java.time.Instant;

public record EnrichedBidActivity(
        String id,
        String auctionId,
        String itemId,
        String itemTitle,
        String itemImageUrl,
        String bidderId,
        String bidderName,
        long latestAmount,
        int bidCount,
        String bidStatus,
        String paymentStatus,
        Instant firstBidAt,
        Instant lastBidAt
) {
    public static EnrichedBidActivity from(BidActivity ba, UserLookup user, ItemLookup item) {
        return new EnrichedBidActivity(
                ba.getId(),
                ba.getAuctionId(),
                ba.getItemId(),
                item != null ? item.getTitle() : null,
                item != null ? item.getImageUrl() : null,
                ba.getBidderId(),
                user != null ? user.getUsername() : null,
                ba.getLatestAmount(),
                ba.getBidCount(),
                ba.getBidStatus(),
                ba.getPaymentStatus(),
                ba.getFirstBidAt(),
                ba.getLastBidAt()
        );
    }
}
