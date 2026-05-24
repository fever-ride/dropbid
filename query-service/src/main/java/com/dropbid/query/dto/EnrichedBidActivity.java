package com.dropbid.query.dto;

import com.dropbid.query.model.ItemLookup;
import com.dropbid.query.model.UserLookup;

import java.time.Instant;

/**
 * Per-(auction, bidder) summary returned to the buyer dashboard and the
 * seller bids view.
 *
 * <p>Built from a {@link BidSummaryProjection} (a native SQL aggregation over
 * the {@code bid} table) enriched with display names from the lookup tables.
 */
public record EnrichedBidActivity(
        String  auctionId,
        String  itemId,
        String  itemTitle,
        String  itemImageUrl,
        String  bidderId,
        String  bidderName,
        long    latestAmount,
        long    bidCount,
        String  bidStatus,
        String  paymentStatus,
        String  paymentId,
        Instant firstBidAt,
        Instant lastBidAt
) {
    public static EnrichedBidActivity from(BidSummaryProjection p,
                                           UserLookup user,
                                           ItemLookup item) {
        return new EnrichedBidActivity(
                p.getAuctionId(),
                p.getItemId(),
                item != null ? item.getTitle()    : null,
                item != null ? item.getImageUrl() : null,
                p.getBidderId(),
                user != null ? user.getUsername() : null,
                p.getLatestAmount(),
                p.getBidCount(),
                p.getBidStatus(),
                p.getPaymentStatus(),
                p.getPaymentId(),
                p.getFirstBidAt(),
                p.getLastBidAt()
        );
    }
}
