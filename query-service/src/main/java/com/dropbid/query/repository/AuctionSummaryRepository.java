package com.dropbid.query.repository;

import com.dropbid.query.model.AuctionSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuctionSummaryRepository extends JpaRepository<AuctionSummary, String> {

    Page<AuctionSummary> findByStatus(String status, Pageable pageable);

    Page<AuctionSummary> findBySellerId(String sellerId, Pageable pageable);

    Page<AuctionSummary> findBySellerIdAndStatus(String sellerId, String status, Pageable pageable);
}
