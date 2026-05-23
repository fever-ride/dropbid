package com.dropbid.auction.repository;

import com.dropbid.auction.model.Auction;

import java.util.List;

public interface AuctionStore {
    void save(Auction auction);
    Auction findById(String auctionId);
    Auction findByIdOrNull(String auctionId);
    List<Auction> findByStatus(String status);
    void update(Auction auction);
    void updateUnconditional(Auction auction);
}
