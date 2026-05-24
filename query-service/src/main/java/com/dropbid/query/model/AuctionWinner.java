package com.dropbid.query.model;

import com.dropbid.shared.IdGenerator;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Records a winning bid after an auction closes.
 *
 * <p>The unique constraint on {@code (auctionId, bidderId)} makes the
 * {@code auction:closed} consumer idempotent: saving the same winner twice
 * simply raises a constraint violation that the consumer can catch and ignore,
 * rather than creating duplicate rows.
 *
 * <p>Payment status is stored here (not on the {@code bid} table) because only
 * winners have a payment lifecycle.
 */
@Entity
@Table(name = "auction_winner",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_auction_winner_auction_bidder",
                columnNames = {"auctionId", "bidderId"}),
        indexes = {
                @Index(name = "idx_aw_auction", columnList = "auctionId"),
                @Index(name = "idx_aw_bidder",  columnList = "bidderId"),
        })
public class AuctionWinner {

    @Id
    private String id;

    @Column(nullable = false)
    private String auctionId;

    @Column(nullable = false)
    private String bidderId;

    @Column(nullable = false)
    private long amount;

    private String paymentStatus;
    private String paymentId;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @PrePersist
    void prePersist() {
        if (id == null) id = IdGenerator.newId();
    }

    // ── getters / setters ────────────────────────────────────────────────────

    public String  getId()                            { return id; }
    public void    setId(String id)                   { this.id = id; }

    public String  getAuctionId()                     { return auctionId; }
    public void    setAuctionId(String auctionId)     { this.auctionId = auctionId; }

    public String  getBidderId()                      { return bidderId; }
    public void    setBidderId(String bidderId)       { this.bidderId = bidderId; }

    public long    getAmount()                        { return amount; }
    public void    setAmount(long amount)             { this.amount = amount; }

    public String  getPaymentStatus()                 { return paymentStatus; }
    public void    setPaymentStatus(String status)    { this.paymentStatus = status; }

    public String  getPaymentId()                     { return paymentId; }
    public void    setPaymentId(String paymentId)     { this.paymentId = paymentId; }

    public Instant getUpdatedAt()                     { return updatedAt; }
    public void    setUpdatedAt(Instant updatedAt)    { this.updatedAt = updatedAt; }
}
