package com.dropbid.bid.model;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.*;

@DynamoDbBean
public class Bid {

    private String bidId;
    private String auctionId;
    private String bidderId;
    private Long   amount;
    private String status;    // ACTIVE | OUTBID | WON
    private String createdAt; // ISO-8601

    @DynamoDbPartitionKey
    @DynamoDbAttribute("bidId")
    public String getBidId()                 { return bidId; }
    public void setBidId(String bidId)       { this.bidId = bidId; }

    @DynamoDbSecondaryPartitionKey(indexNames = "auction-index")
    public String getAuctionId()                 { return auctionId; }
    public void setAuctionId(String auctionId)   { this.auctionId = auctionId; }

    @DynamoDbSecondaryPartitionKey(indexNames = "bidder-index")
    public String getBidderId()                  { return bidderId; }
    public void setBidderId(String bidderId)     { this.bidderId = bidderId; }

    public Long getAmount()              { return amount; }
    public void setAmount(Long amount)   { this.amount = amount; }

    public String getStatus()                { return status; }
    public void setStatus(String status)     { this.status = status; }

    @DynamoDbSecondarySortKey(indexNames = {"auction-index", "bidder-index"})
    public String getCreatedAt()                 { return createdAt; }
    public void setCreatedAt(String createdAt)   { this.createdAt = createdAt; }
}
