package com.dropbid.query.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Append-only record of a single accepted bid.
 *
 * <p>{@code bidId} is the idempotency key: the {@code bid_placed} consumer
 * checks {@code existsById(bidId)} before writing, so replayed PEL messages
 * are silently ignored without any per-(auction, bidder) aggregation state.
 *
 * <p>Queries that need per-bidder summaries (buyer dashboard, seller bids view)
 * use GROUP BY over this table rather than maintaining a separate denormalised
 * row that would need careful update logic.
 */
@Entity
@Table(name = "bid", indexes = {
        @Index(name = "idx_bid_auction",        columnList = "auctionId"),
        @Index(name = "idx_bid_bidder",         columnList = "bidderId"),
        @Index(name = "idx_bid_bidder_bidat",   columnList = "bidderId, bidAt"),
        @Index(name = "idx_bid_auction_amount", columnList = "auctionId, amount"),
})
public class Bid {

    /** Carried directly from {@code BidPlacedEvent.bidId}. */
    @Id
    private String bidId;

    @Column(nullable = false)
    private String auctionId;

    @Column(nullable = false)
    private String bidderId;

    /** Denormalised for item enrichment at query time without a JOIN to auction. */
    @Column(nullable = false)
    private String itemId;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false)
    private Instant bidAt;

    // ── getters / setters ────────────────────────────────────────────────────

    public String  getBidId()                    { return bidId; }
    public void    setBidId(String bidId)        { this.bidId = bidId; }

    public String  getAuctionId()                { return auctionId; }
    public void    setAuctionId(String id)       { this.auctionId = id; }

    public String  getBidderId()                 { return bidderId; }
    public void    setBidderId(String id)        { this.bidderId = id; }

    public String  getItemId()                   { return itemId; }
    public void    setItemId(String id)          { this.itemId = id; }

    public long    getAmount()                   { return amount; }
    public void    setAmount(long amount)        { this.amount = amount; }

    public Instant getBidAt()                    { return bidAt; }
    public void    setBidAt(Instant bidAt)       { this.bidAt = bidAt; }
}
