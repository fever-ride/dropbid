package com.dropbid.auction.bid.repository;

import com.dropbid.auction.bid.model.Bid;

import java.util.List;

public interface BidStore {
    void save(Bid bid);
    Bid findById(String bidId);
    List<Bid> findByAuctionId(String auctionId);
    List<Bid> findByBidderId(String bidderId);
}
