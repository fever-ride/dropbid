package com.dropbid.query.controller;

import com.dropbid.query.dto.EnrichedAuctionSummary;
import com.dropbid.query.dto.EnrichedBidActivity;
import com.dropbid.query.model.Auction;
import com.dropbid.query.repository.AuctionRepository;
import com.dropbid.query.repository.BidRepository;
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

    private final AuctionRepository auctionRepo;
    private final BidRepository     bidRepo;
    private final EnrichmentService enrichment;

    public SellerQueryController(AuctionRepository auctionRepo,
                                  BidRepository bidRepo,
                                  EnrichmentService enrichment) {
        this.auctionRepo = auctionRepo;
        this.bidRepo     = bidRepo;
        this.enrichment  = enrichment;
    }

    @GetMapping("/auctions")
    public Page<EnrichedAuctionSummary> myAuctions(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        PageRequest pageable = PageRequest.of(page, size, Sort.by("updatedAt").descending());

        Page<Auction> auctions;
        if (status != null && !status.isBlank()) {
            auctions = auctionRepo.findBySellerIdAndStatus(
                    principal.userId(), status.toUpperCase(), pageable);
        } else {
            auctions = auctionRepo.findBySellerId(principal.userId(), pageable);
        }
        return enrichment.enrichAuctions(auctions);
    }

    /**
     * Returns all bidders on a given auction, each showing their highest bid,
     * total bid count, and current status (WON / OUTBID / ACTIVE).
     * Results are sorted by highest bid descending.
     */
    @GetMapping("/auctions/{auctionId}/bids")
    public List<EnrichedBidActivity> auctionBids(@PathVariable String auctionId) {
        return enrichment.enrichBidList(bidRepo.findBidSummariesByAuctionId(auctionId));
    }
}
