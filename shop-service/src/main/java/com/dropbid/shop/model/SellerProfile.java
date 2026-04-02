package com.dropbid.shop.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "seller_profiles")
public class SellerProfile {

    @Id
    private String id;

    @Column(name = "owner_id", nullable = false, unique = true)
    private String ownerId;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String bio;

    @Column(nullable = false, precision = 3, scale = 2)
    private BigDecimal rating = BigDecimal.ZERO;

    @Column(nullable = false)
    private boolean verified = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null)        id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = Instant.now();
    }

    // ── Getters / Setters ──────────────────────────────────────────────────

    public String getId()                { return id; }
    public void setId(String id)         { this.id = id; }

    public String getOwnerId()               { return ownerId; }
    public void setOwnerId(String ownerId)   { this.ownerId = ownerId; }

    public String getName()              { return name; }
    public void setName(String name)     { this.name = name; }

    public String getBio()               { return bio; }
    public void setBio(String bio)       { this.bio = bio; }

    public BigDecimal getRating()                { return rating; }
    public void setRating(BigDecimal rating)     { this.rating = rating; }

    public boolean isVerified()              { return verified; }
    public void setVerified(boolean verified) { this.verified = verified; }

    public Instant getCreatedAt()               { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
