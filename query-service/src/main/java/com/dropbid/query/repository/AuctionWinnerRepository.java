package com.dropbid.query.repository;

import com.dropbid.query.model.AuctionWinner;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AuctionWinnerRepository extends JpaRepository<AuctionWinner, String> {

    Optional<AuctionWinner> findByAuctionIdAndBidderId(String auctionId, String bidderId);

    boolean existsByAuctionIdAndBidderId(String auctionId, String bidderId);
}
