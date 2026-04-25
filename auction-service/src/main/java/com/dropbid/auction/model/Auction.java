package com.dropbid.auction.model;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.*;

import java.util.Map;

/**
 * DynamoDB-backed auction record.
 * Redis hash (auction:{auctionId}) is the hot path for live bid state.
 * DynamoDB is source of truth for persistence.
 */
@DynamoDbBean
public class Auction {

    private String auctionId;
    private String itemId;
    private String shopId;
    private String sellerId;
    private Long   startingBid;
    private Long   maxPrice;      // ceiling — bids may not exceed the item's original price
    private Long   currentHighest;
    private String highestBidder;
    private String status;        // PENDING | OPEN | CLOSED
    private Long   quantity;               // number of winners; defaults to 1
    private Map<String, Long> winners;    // bidderId → winning amount; persisted on every bid
    private String startTime;     // ISO-8601
    private String endTime;       // ISO-8601
    private Long   bidCount;
    private Long   version;       // Optimistic locking

    @DynamoDbPartitionKey
    @DynamoDbAttribute("auctionId")
    public String getAuctionId()                 { return auctionId; }
    public void setAuctionId(String auctionId)   { this.auctionId = auctionId; }

    public String getItemId()                { return itemId; }
    public void setItemId(String itemId)     { this.itemId = itemId; }

    public String getShopId()                { return shopId; }
    public void setShopId(String shopId)     { this.shopId = shopId; }

    @DynamoDbSecondaryPartitionKey(indexNames = "seller-index")
    public String getSellerId()                  { return sellerId; }
    public void setSellerId(String sellerId)     { this.sellerId = sellerId; }

    public Long getStartingBid()                     { return startingBid; }
    public void setStartingBid(Long startingBid)     { this.startingBid = startingBid; }

    public Long getMaxPrice()                    { return maxPrice; }
    public void setMaxPrice(Long maxPrice)       { this.maxPrice = maxPrice; }

    public Long getCurrentHighest()                          { return currentHighest; }
    public void setCurrentHighest(Long currentHighest)       { this.currentHighest = currentHighest; }

    public String getHighestBidder()                     { return highestBidder; }
    public void setHighestBidder(String highestBidder)   { this.highestBidder = highestBidder; }

    @DynamoDbSecondaryPartitionKey(indexNames = "status-index")
    public String getStatus()                { return status; }
    public void setStatus(String status)     { this.status = status; }

    public String getStartTime()                 { return startTime; }
    public void setStartTime(String startTime)   { this.startTime = startTime; }

    public String getEndTime()               { return endTime; }
    public void setEndTime(String endTime)   { this.endTime = endTime; }

    public Long getBidCount()                    { return bidCount; }
    public void setBidCount(Long bidCount)       { this.bidCount = bidCount; }

    public Long getVersion()                 { return version; }
    public void setVersion(Long version)     { this.version = version; }

    public Long getQuantity()                { return quantity; }
    public void setQuantity(Long quantity)   { this.quantity = quantity; }

    public Map<String, Long> getWinners()                      { return winners; }
    public void setWinners(Map<String, Long> winners)          { this.winners = winners; }
}
