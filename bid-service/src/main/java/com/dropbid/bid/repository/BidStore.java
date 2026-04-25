package com.dropbid.bid.repository;

import com.dropbid.bid.model.Bid;

import java.util.List;

public interface BidStore {
    void save(Bid bid);
    Bid findById(String bidId);
    List<Bid> findByAuctionId(String auctionId);
    List<Bid> findByBidderId(String bidderId);
    void update(Bid bid);
}
