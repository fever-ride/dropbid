package com.dropbid.query.controller;

import com.dropbid.query.dto.BidSummaryProjection;
import com.dropbid.query.dto.EnrichedBidActivity;
import com.dropbid.query.repository.BidRepository;
import com.dropbid.shared.security.UserPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/query/my")
@PreAuthorize("hasRole('BUYER')")
public class BuyerQueryController {

    private final BidRepository  bidRepo;
    private final EnrichmentService enrichment;

    public BuyerQueryController(BidRepository bidRepo, EnrichmentService enrichment) {
        this.bidRepo    = bidRepo;
        this.enrichment = enrichment;
    }

    /**
     * Returns a page of auctions the authenticated buyer has bid on, each
     * summarised as their highest bid amount and derived status (ACTIVE /
     * OUTBID / WON).
     *
     * <p>Pagination uses explicit LIMIT / OFFSET in the native SQL query so
     * that the aggregation and status filter happen entirely in the database
     * before any rows are transferred to the application.
     */
    @GetMapping("/bids")
    public Page<EnrichedBidActivity> myBids(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        String bidderId = principal.userId();
        int offset = page * size;

        List<BidSummaryProjection> content;
        long total;

        if (status != null && !status.isBlank()) {
            String upper = status.toUpperCase();
            content = bidRepo.findBidSummariesByBidderIdAndStatus(bidderId, upper, size, offset);
            total   = bidRepo.countBidSummariesByBidderIdAndStatus(bidderId, upper);
        } else {
            content = bidRepo.findBidSummariesByBidderId(bidderId, size, offset);
            total   = bidRepo.countDistinctAuctionsByBidderId(bidderId);
        }

        Page<BidSummaryProjection> paged = new PageImpl<>(content, PageRequest.of(page, size), total);
        return enrichment.enrichBids(paged);
    }
}
