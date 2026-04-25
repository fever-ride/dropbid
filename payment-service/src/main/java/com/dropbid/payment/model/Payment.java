package com.dropbid.payment.model;

import com.dropbid.shared.IdGenerator;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "payments")
public class Payment {

    @Id
    private String id;

    @Column(name = "auction_id", nullable = false)
    private String auctionId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "fail_reason", columnDefinition = "TEXT")
    private String failReason;

    /**
     * Once the mock gateway makes a decision, we store it here.
     * Subsequent retries re-use this decision rather than re-rolling,
     * making the retry path idempotent.
     */
    @Column(name = "gateway_decision")
    private String gatewayDecision;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        if (id == null)        id = IdGenerator.newId();
        if (createdAt == null) createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    // ── Getters / Setters ──────────────────────────────────────────────────

    public String getId()            { return id; }
    public void setId(String id)     { this.id = id; }

    public String getAuctionId()                 { return auctionId; }
    public void setAuctionId(String auctionId)   { this.auctionId = auctionId; }

    public String getUserId()                { return userId; }
    public void setUserId(String userId)     { this.userId = userId; }

    public Long getAmount()              { return amount; }
    public void setAmount(Long amount)   { this.amount = amount; }

    public PaymentStatus getStatus()                 { return status; }
    public void setStatus(PaymentStatus status)      { this.status = status; }

    public String getFailReason()                    { return failReason; }
    public void setFailReason(String failReason)     { this.failReason = failReason; }

    public String getGatewayDecision()                       { return gatewayDecision; }
    public void setGatewayDecision(String gatewayDecision)   { this.gatewayDecision = gatewayDecision; }

    public int getRetryCount()               { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public Instant getCreatedAt()               { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt()               { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
