package com.dropbid.query.controller;

import com.dropbid.query.dto.EnrichedAuctionSummary;
import com.dropbid.query.model.Auction;
import com.dropbid.query.repository.AuctionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/query/auctions")
public class PublicQueryController {

    private final AuctionRepository auctionRepo;
    private final EnrichmentService enrichment;

    public PublicQueryController(AuctionRepository auctionRepo, EnrichmentService enrichment) {
        this.auctionRepo = auctionRepo;
        this.enrichment  = enrichment;
    }

    @GetMapping
    public Page<EnrichedAuctionSummary> listAuctions(
            @RequestParam(defaultValue = "OPEN")     String status,
            @RequestParam(defaultValue = "bidCount")  String sort,
            @RequestParam(defaultValue = "0")          int    page,
            @RequestParam(defaultValue = "20")         int    size) {

        // bidCount and currentHighest are stored on the auction row, so
        // ORDER BY them does not require a GROUP BY aggregation at query time.
        Sort sortBy = switch (sort) {
            case "currentHighest" -> Sort.by("currentHighest").descending();
            case "updatedAt"      -> Sort.by("updatedAt").descending();
            default               -> Sort.by("bidCount").descending();
        };

        Page<Auction> auctions = auctionRepo.findByStatus(
                status.toUpperCase(), PageRequest.of(page, size, sortBy));
        return enrichment.enrichAuctions(auctions);
    }
}
