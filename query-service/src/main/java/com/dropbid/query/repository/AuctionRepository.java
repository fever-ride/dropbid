package com.dropbid.query.repository;

import com.dropbid.query.model.Auction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface AuctionRepository extends JpaRepository<Auction, String> {

    Page<Auction> findByStatus(String status, Pageable pageable);

    Page<Auction> findBySellerId(String sellerId, Pageable pageable);

    Page<Auction> findBySellerIdAndStatus(String sellerId, String status, Pageable pageable);

    /**
     * Atomically increments {@code bidCount} and updates {@code currentHighest}
     * to the maximum of the stored value and the new bid amount.
     *
     * <p>Returns the number of rows affected (1 if the auction row exists,
     * 0 if it does not — the caller can then create the row defensively).
     */
    @Modifying
    @Query(value = """
            UPDATE auction
               SET bid_count       = bid_count + 1,
                   current_highest = GREATEST(current_highest, :amount),
                   updated_at      = :now
             WHERE auction_id = :auctionId
            """, nativeQuery = true)
    int incrementBidCounters(@Param("auctionId") String auctionId,
                             @Param("amount")    long    amount,
                             @Param("now")       Instant now);
}
