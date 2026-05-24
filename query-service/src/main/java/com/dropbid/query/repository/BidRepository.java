package com.dropbid.query.repository;

import com.dropbid.query.dto.BidSummaryProjection;
import com.dropbid.query.model.Bid;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BidRepository extends JpaRepository<Bid, String> {

    // ── Buyer dashboard: per-(auction, bidder) summaries paged by last bid ──

    /** All auctions a bidder has participated in, newest first. */
    @Query(value = """
            SELECT b.auction_id    AS auctionId,
                   b.bidder_id     AS bidderId,
                   b.item_id       AS itemId,
                   MAX(b.amount)   AS latestAmount,
                   COUNT(*)        AS bidCount,
                   MIN(b.bid_at)   AS firstBidAt,
                   MAX(b.bid_at)   AS lastBidAt,
                   a.status        AS auctionStatus,
                   CASE WHEN aw.bidder_id IS NOT NULL THEN 'WON'
                        WHEN a.status = 'CLOSED'      THEN 'OUTBID'
                        ELSE 'ACTIVE' END             AS bidStatus,
                   aw.payment_status AS paymentStatus,
                   aw.payment_id     AS paymentId
              FROM bid b
              JOIN auction a ON a.auction_id = b.auction_id
              LEFT JOIN auction_winner aw
                     ON aw.auction_id = b.auction_id AND aw.bidder_id = b.bidder_id
             WHERE b.bidder_id = :bidderId
             GROUP BY b.auction_id, b.bidder_id, b.item_id, a.status,
                      aw.bidder_id, aw.payment_status, aw.payment_id
             ORDER BY MAX(b.bid_at) DESC
             LIMIT :lim OFFSET :off
            """, nativeQuery = true)
    List<BidSummaryProjection> findBidSummariesByBidderId(
            @Param("bidderId") String bidderId,
            @Param("lim")      int    lim,
            @Param("off")      int    off);

    @Query(value = "SELECT COUNT(DISTINCT auction_id) FROM bid WHERE bidder_id = :bidderId",
           nativeQuery = true)
    long countDistinctAuctionsByBidderId(@Param("bidderId") String bidderId);

    /** Same as above filtered by computed bid status. */
    @Query(value = """
            SELECT b.auction_id    AS auctionId,
                   b.bidder_id     AS bidderId,
                   b.item_id       AS itemId,
                   MAX(b.amount)   AS latestAmount,
                   COUNT(*)        AS bidCount,
                   MIN(b.bid_at)   AS firstBidAt,
                   MAX(b.bid_at)   AS lastBidAt,
                   a.status        AS auctionStatus,
                   CASE WHEN aw.bidder_id IS NOT NULL THEN 'WON'
                        WHEN a.status = 'CLOSED'      THEN 'OUTBID'
                        ELSE 'ACTIVE' END             AS bidStatus,
                   aw.payment_status AS paymentStatus,
                   aw.payment_id     AS paymentId
              FROM bid b
              JOIN auction a ON a.auction_id = b.auction_id
              LEFT JOIN auction_winner aw
                     ON aw.auction_id = b.auction_id AND aw.bidder_id = b.bidder_id
             WHERE b.bidder_id = :bidderId
             GROUP BY b.auction_id, b.bidder_id, b.item_id, a.status,
                      aw.bidder_id, aw.payment_status, aw.payment_id
            HAVING CASE WHEN aw.bidder_id IS NOT NULL THEN 'WON'
                        WHEN a.status = 'CLOSED'      THEN 'OUTBID'
                        ELSE 'ACTIVE' END = :bidStatus
             ORDER BY MAX(b.bid_at) DESC
             LIMIT :lim OFFSET :off
            """, nativeQuery = true)
    List<BidSummaryProjection> findBidSummariesByBidderIdAndStatus(
            @Param("bidderId")  String bidderId,
            @Param("bidStatus") String bidStatus,
            @Param("lim")       int    lim,
            @Param("off")       int    off);

    @Query(value = """
            SELECT COUNT(*) FROM (
                SELECT b.auction_id
                  FROM bid b
                  JOIN auction a ON a.auction_id = b.auction_id
                  LEFT JOIN auction_winner aw
                         ON aw.auction_id = b.auction_id AND aw.bidder_id = b.bidder_id
                 WHERE b.bidder_id = :bidderId
                 GROUP BY b.auction_id, b.bidder_id, a.status,
                          aw.bidder_id, aw.payment_status, aw.payment_id
                HAVING CASE WHEN aw.bidder_id IS NOT NULL THEN 'WON'
                            WHEN a.status = 'CLOSED'      THEN 'OUTBID'
                            ELSE 'ACTIVE' END = :bidStatus
            ) sub
            """, nativeQuery = true)
    long countBidSummariesByBidderIdAndStatus(
            @Param("bidderId")  String bidderId,
            @Param("bidStatus") String bidStatus);

    // ── Seller bids view: per-bidder summaries for one auction ──────────────

    /** All bidders on a given auction with their aggregate stats, sorted by
     *  highest bid descending. */
    @Query(value = """
            SELECT b.auction_id    AS auctionId,
                   b.bidder_id     AS bidderId,
                   b.item_id       AS itemId,
                   MAX(b.amount)   AS latestAmount,
                   COUNT(*)        AS bidCount,
                   MIN(b.bid_at)   AS firstBidAt,
                   MAX(b.bid_at)   AS lastBidAt,
                   a.status        AS auctionStatus,
                   CASE WHEN aw.bidder_id IS NOT NULL THEN 'WON'
                        WHEN a.status = 'CLOSED'      THEN 'OUTBID'
                        ELSE 'ACTIVE' END             AS bidStatus,
                   aw.payment_status AS paymentStatus,
                   aw.payment_id     AS paymentId
              FROM bid b
              JOIN auction a ON a.auction_id = b.auction_id
              LEFT JOIN auction_winner aw
                     ON aw.auction_id = b.auction_id AND aw.bidder_id = b.bidder_id
             WHERE b.auction_id = :auctionId
             GROUP BY b.auction_id, b.bidder_id, b.item_id, a.status,
                      aw.bidder_id, aw.payment_status, aw.payment_id
             ORDER BY MAX(b.amount) DESC
            """, nativeQuery = true)
    List<BidSummaryProjection> findBidSummariesByAuctionId(@Param("auctionId") String auctionId);
}
