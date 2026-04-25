package com.dropbid.query.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "auction_summary")
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

    private Instant closedAt;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

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

    public Instant getClosedAt()                     { return closedAt; }
    public void setClosedAt(Instant closedAt)        { this.closedAt = closedAt; }

    public Instant getUpdatedAt()                    { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt)      { this.updatedAt = updatedAt; }
}
