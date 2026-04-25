package com.dropbid.query.controller;

import com.dropbid.query.dto.EnrichedAuctionSummary;
import com.dropbid.query.dto.EnrichedBidActivity;
import com.dropbid.query.model.AuctionSummary;
import com.dropbid.query.repository.AuctionSummaryRepository;
import com.dropbid.query.repository.BidActivityRepository;
import com.dropbid.shared.security.UserPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/query/seller")
@PreAuthorize("hasRole('SELLER')")
public class SellerQueryController {

    private final AuctionSummaryRepository auctionRepo;
    private final BidActivityRepository    bidRepo;
    private final EnrichmentService        enrichment;

    public SellerQueryController(AuctionSummaryRepository auctionRepo,
                                  BidActivityRepository bidRepo,
                                  EnrichmentService enrichment) {
        this.auctionRepo = auctionRepo;
        this.bidRepo     = bidRepo;
        this.enrichment  = enrichment;
    }

    @GetMapping("/auctions")
    public Page<EnrichedAuctionSummary> myAuctions(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PageRequest pageable = PageRequest.of(page, size, Sort.by("updatedAt").descending());

        Page<AuctionSummary> auctions;
        if (status != null && !status.isBlank()) {
            auctions = auctionRepo.findBySellerIdAndStatus(principal.userId(), status.toUpperCase(), pageable);
        } else {
            auctions = auctionRepo.findBySellerId(principal.userId(), pageable);
        }
        return enrichment.enrichAuctions(auctions);
    }

    @GetMapping("/auctions/{auctionId}/bids")
    public List<EnrichedBidActivity> auctionBids(@PathVariable String auctionId) {
        return enrichment.enrichBidList(bidRepo.findByAuctionId(auctionId));
    }
}
