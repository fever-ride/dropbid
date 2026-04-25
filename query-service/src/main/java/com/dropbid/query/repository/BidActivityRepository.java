package com.dropbid.query.repository;

import com.dropbid.query.model.BidActivity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BidActivityRepository extends JpaRepository<BidActivity, String> {

    Optional<BidActivity> findByAuctionIdAndBidderId(String auctionId, String bidderId);

    Page<BidActivity> findByBidderId(String bidderId, Pageable pageable);

    Page<BidActivity> findByBidderIdAndBidStatus(String bidderId, String bidStatus, Pageable pageable);

    List<BidActivity> findByAuctionId(String auctionId);

    @Query("""
        SELECT ba FROM BidActivity ba
        WHERE ba.auctionId = :auctionId AND ba.bidderId IN :bidderIds
    """)
    List<BidActivity> findByAuctionIdAndBidderIdIn(
            @Param("auctionId") String auctionId,
            @Param("bidderIds") List<String> bidderIds);
}
