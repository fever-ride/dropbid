package com.dropbid.query.controller;

import com.dropbid.query.dto.EnrichedBidActivity;
import com.dropbid.query.model.BidActivity;
import com.dropbid.query.repository.BidActivityRepository;
import com.dropbid.shared.security.UserPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/query/my")
@PreAuthorize("hasRole('BUYER')")
public class BuyerQueryController {

    private final BidActivityRepository bidRepo;
    private final EnrichmentService enrichment;

    public BuyerQueryController(BidActivityRepository bidRepo, EnrichmentService enrichment) {
        this.bidRepo    = bidRepo;
        this.enrichment = enrichment;
    }

    @GetMapping("/bids")
    public Page<EnrichedBidActivity> myBids(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PageRequest pageable = PageRequest.of(page, size, Sort.by("lastBidAt").descending());

        Page<BidActivity> bids;
        if (status != null && !status.isBlank()) {
            bids = bidRepo.findByBidderIdAndBidStatus(principal.userId(), status.toUpperCase(), pageable);
        } else {
            bids = bidRepo.findByBidderId(principal.userId(), pageable);
        }
        return enrichment.enrichBids(bids);
    }
}
