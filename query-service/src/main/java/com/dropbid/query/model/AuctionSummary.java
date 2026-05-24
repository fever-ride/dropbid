package com.dropbid.query.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "auction_summary", indexes = {
        // single-column: used by findByStatus, findBySellerId
        @Index(name = "idx_as_status",    columnList = "status"),
        @Index(name = "idx_as_seller",    columnList = "sellerId"),
        // compound: supports the three sort options on the public listing endpoint
        @Index(name = "idx_as_status_bidcount",      columnList = "status, bidCount"),
        @Index(name = "idx_as_status_highest",        columnList = "status, currentHighest"),
        @Index(name = "idx_as_status_updatedat",      columnList = "status, updatedAt"),
        // seller dashboard with status filter
        @Index(name = "idx_as_seller_status",         columnList = "sellerId, status")
})
public class AuctionSummary {

    @Id
    private String auctionId;

    @Column(nullable = false)
    private String itemId;

    private String shopId;
    private String sellerId;

    @Column(nullable = false)
    private String status = "OPEN";

    @Column(nullable = false)
    private long currentHighest;

    @Column(nullable = false)
    private long bidCount;

    // Populated via auction:created event (not yet implemented).
    // Required for "auction ends in X" display and winner-slot count.
    private String endTime;
    private Long   quantity;

    private Instant closedAt;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    // Idempotency token: the bidId of the last bid_placed event processed for
    // this auction.  Prevents double-counting bidCount on PEL redelivery.
    private String lastBidId;

    public String getAuctionId()                     { return auctionId; }
    public void setAuctionId(String auctionId)       { this.auctionId = auctionId; }

    public String getItemId()                        { return itemId; }
    public void setItemId(String itemId)             { this.itemId = itemId; }

    public String getShopId()                        { return shopId; }
    public void setShopId(String shopId)             { this.shopId = shopId; }

    public String getSellerId()                      { return sellerId; }
    public void setSellerId(String sellerId)         { this.sellerId = sellerId; }

    public String getStatus()                        { return status; }
    public void setStatus(String status)             { this.status = status; }

    public long getCurrentHighest()                  { return currentHighest; }
    public void setCurrentHighest(long currentHighest) { this.currentHighest = currentHighest; }

    public long getBidCount()                        { return bidCount; }
    public void setBidCount(long bidCount)           { this.bidCount = bidCount; }

    public String getEndTime()                       { return endTime; }
    public void setEndTime(String endTime)           { this.endTime = endTime; }

    public Long getQuantity()                        { return quantity; }
    public void setQuantity(Long quantity)           { this.quantity = quantity; }

    public String getLastBidId()                     { return lastBidId; }
    public void setLastBidId(String lastBidId)       { this.lastBidId = lastBidId; }

    public Instant getClosedAt()                     { return closedAt; }
    public void setClosedAt(Instant closedAt)        { this.closedAt = closedAt; }

    public Instant getUpdatedAt()                    { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt)      { this.updatedAt = updatedAt; }
}
