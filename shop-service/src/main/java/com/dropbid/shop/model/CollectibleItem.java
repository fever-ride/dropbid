package com.dropbid.shop.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "collectible_items")
public class CollectibleItem {

    @Id
    private String id;

    @Column(name = "shop_id", nullable = false)
    private String shopId;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    private String series;
    private String edition;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Condition condition;

    @Column(name = "original_retail_price", nullable = false)
    private long originalRetailPrice;

    @Column(name = "estimated_market_value", nullable = false)
    private long estimatedMarketValue;

    @Column(name = "image_url", columnDefinition = "TEXT")
    private String imageUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null)        id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = Instant.now();
    }

    // ── Getters / Setters ──────────────────────────────────────────────────

    public String getId()            { return id; }
    public void setId(String id)     { this.id = id; }

    public String getShopId()                { return shopId; }
    public void setShopId(String shopId)     { this.shopId = shopId; }

    public String getTitle()             { return title; }
    public void setTitle(String title)   { this.title = title; }

    public String getDescription()                   { return description; }
    public void setDescription(String description)   { this.description = description; }

    public String getSeries()                { return series; }
    public void setSeries(String series)     { this.series = series; }

    public String getEdition()               { return edition; }
    public void setEdition(String edition)   { this.edition = edition; }

    public Condition getCondition()                  { return condition; }
    public void setCondition(Condition condition)    { this.condition = condition; }

    public long getOriginalRetailPrice()                         { return originalRetailPrice; }
    public void setOriginalRetailPrice(long originalRetailPrice) { this.originalRetailPrice = originalRetailPrice; }

    public long getEstimatedMarketValue()                          { return estimatedMarketValue; }
    public void setEstimatedMarketValue(long estimatedMarketValue) { this.estimatedMarketValue = estimatedMarketValue; }

    public String getImageUrl()                { return imageUrl; }
    public void setImageUrl(String imageUrl)   { this.imageUrl = imageUrl; }

    public Instant getCreatedAt()               { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
