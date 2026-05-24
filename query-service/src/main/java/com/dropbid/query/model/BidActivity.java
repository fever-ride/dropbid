package com.dropbid.query.model;

import com.dropbid.shared.IdGenerator;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "bid_activity",
       uniqueConstraints = @UniqueConstraint(columnNames = {"auctionId", "bidderId"}),
       indexes = {
               // buyer dashboard: all bids by a user, optionally filtered by status
               @Index(name = "idx_ba_bidder",             columnList = "bidderId"),
               @Index(name = "idx_ba_bidder_status",      columnList = "bidderId, bidStatus"),
               // seller dashboard: all bids on one auction
               @Index(name = "idx_ba_auction",            columnList = "auctionId")
       })
public class BidActivity {

    @Id
    private String id;

    @Column(nullable = false)
    private String auctionId;

    @Column(nullable = false)
    private String itemId;

    @Column(nullable = false)
    private String bidderId;

    @Column(nullable = false)
    private long latestAmount;

    @Column(nullable = false)
    private int bidCount = 1;

    @Column(nullable = false)
    private String bidStatus = "ACTIVE";

    private String paymentStatus;
    private String paymentId;

    @Column(nullable = false)
    private Instant firstBidAt;

    @Column(nullable = false)
    private Instant lastBidAt;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @PrePersist
    void prePersist() {
        if (id == null) id = IdGenerator.newId();
    }

    public String getId()                            { return id; }
    public void setId(String id)                     { this.id = id; }

    public String getAuctionId()                     { return auctionId; }
    public void setAuctionId(String auctionId)       { this.auctionId = auctionId; }

    public String getItemId()                        { return itemId; }
    public void setItemId(String itemId)             { this.itemId = itemId; }

    public String getBidderId()                      { return bidderId; }
    public void setBidderId(String bidderId)         { this.bidderId = bidderId; }

    public long getLatestAmount()                    { return latestAmount; }
    public void setLatestAmount(long latestAmount)   { this.latestAmount = latestAmount; }

    public int getBidCount()                         { return bidCount; }
    public void setBidCount(int bidCount)            { this.bidCount = bidCount; }

    public String getBidStatus()                     { return bidStatus; }
    public void setBidStatus(String bidStatus)       { this.bidStatus = bidStatus; }

    public String getPaymentStatus()                 { return paymentStatus; }
    public void setPaymentStatus(String paymentStatus) { this.paymentStatus = paymentStatus; }

    public String getPaymentId()                     { return paymentId; }
    public void setPaymentId(String paymentId)       { this.paymentId = paymentId; }

    public Instant getFirstBidAt()                   { return firstBidAt; }
    public void setFirstBidAt(Instant firstBidAt)    { this.firstBidAt = firstBidAt; }

    public Instant getLastBidAt()                    { return lastBidAt; }
    public void setLastBidAt(Instant lastBidAt)      { this.lastBidAt = lastBidAt; }

    public Instant getUpdatedAt()                    { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt)      { this.updatedAt = updatedAt; }
}
