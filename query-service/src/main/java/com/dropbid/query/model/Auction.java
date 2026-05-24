package com.dropbid.query.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Read-model projection of an auction.
 *
 * <p>Structural fields (itemId, sellerId, endTime, …) are populated by the
 * {@code auction:created} consumer.  {@code bidCount} and {@code currentHighest}
 * are incremented atomically by the {@code bid_placed} consumer so that the
 * public listing endpoint can ORDER BY them without a GROUP BY aggregation at
 * query time.  These stored values are redundant with {@code COUNT(bid)} /
 * {@code MAX(bid.amount)} but exist purely as a query-time optimisation.
 *
 * <p>No foreign-key constraints are defined — the read model tolerates
 * temporary orphan rows while events propagate across streams.
 */
@Entity
@Table(name = "auction", indexes = {
        @Index(name = "idx_auction_status",           columnList = "status"),
        @Index(name = "idx_auction_seller",           columnList = "sellerId"),
        @Index(name = "idx_auction_seller_status",    columnList = "sellerId, status"),
        @Index(name = "idx_auction_status_bidcount",  columnList = "status, bidCount"),
        @Index(name = "idx_auction_status_highest",   columnList = "status, currentHighest"),
        @Index(name = "idx_auction_status_updatedat", columnList = "status, updatedAt"),
})
public class Auction {

    @Id
    private String auctionId;

    private String itemId;
    private String shopId;
    private String sellerId;

    @Column(nullable = false)
    private String status = "OPEN";

    /** The floor price carried by the auction:created event. */
    @Column(nullable = false)
    private long startingBid;

    private String startTime;
    private String endTime;
    private Long   quantity;

    /** Incremented by the bid_placed consumer; equals COUNT(*) on the bid table. */
    @Column(nullable = false)
    private long bidCount;

    /** Mirrors MAX(bid.amount); starts at startingBid when no bids exist. */
    @Column(nullable = false)
    private long currentHighest;

    private Instant closedAt;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    // ── getters / setters ────────────────────────────────────────────────────

    public String getAuctionId()                       { return auctionId; }
    public void   setAuctionId(String auctionId)       { this.auctionId = auctionId; }

    public String getItemId()                          { return itemId; }
    public void   setItemId(String itemId)             { this.itemId = itemId; }

    public String getShopId()                          { return shopId; }
    public void   setShopId(String shopId)             { this.shopId = shopId; }

    public String getSellerId()                        { return sellerId; }
    public void   setSellerId(String sellerId)         { this.sellerId = sellerId; }

    public String getStatus()                          { return status; }
    public void   setStatus(String status)             { this.status = status; }

    public long   getStartingBid()                     { return startingBid; }
    public void   setStartingBid(long startingBid)     { this.startingBid = startingBid; }

    public String getStartTime()                       { return startTime; }
    public void   setStartTime(String startTime)       { this.startTime = startTime; }

    public String getEndTime()                         { return endTime; }
    public void   setEndTime(String endTime)           { this.endTime = endTime; }

    public Long   getQuantity()                        { return quantity; }
    public void   setQuantity(Long quantity)           { this.quantity = quantity; }

    public long   getBidCount()                        { return bidCount; }
    public void   setBidCount(long bidCount)           { this.bidCount = bidCount; }

    public long   getCurrentHighest()                  { return currentHighest; }
    public void   setCurrentHighest(long currentHighest) { this.currentHighest = currentHighest; }

    public Instant getClosedAt()                       { return closedAt; }
    public void    setClosedAt(Instant closedAt)       { this.closedAt = closedAt; }

    public Instant getUpdatedAt()                      { return updatedAt; }
    public void    setUpdatedAt(Instant updatedAt)     { this.updatedAt = updatedAt; }
}
