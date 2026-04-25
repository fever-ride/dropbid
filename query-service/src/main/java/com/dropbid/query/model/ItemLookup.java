package com.dropbid.query.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "item_lookup")
public class ItemLookup {

    @Id
    private String itemId;

    @Column(nullable = false)
    private String shopId;

    @Column(nullable = false)
    private String title;

    private String imageUrl;
    private String series;
    private String condition;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    public String getItemId()                    { return itemId; }
    public void setItemId(String itemId)         { this.itemId = itemId; }

    public String getShopId()                    { return shopId; }
    public void setShopId(String shopId)         { this.shopId = shopId; }

    public String getTitle()                     { return title; }
    public void setTitle(String title)           { this.title = title; }

    public String getImageUrl()                  { return imageUrl; }
    public void setImageUrl(String imageUrl)     { this.imageUrl = imageUrl; }

    public String getSeries()                    { return series; }
    public void setSeries(String series)         { this.series = series; }

    public String getCondition()                 { return condition; }
    public void setCondition(String condition)   { this.condition = condition; }

    public Instant getUpdatedAt()                { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt)  { this.updatedAt = updatedAt; }
}
