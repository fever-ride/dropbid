package com.dropbid.auction.controller;

import com.dropbid.auction.concurrency.BidResult;
import com.dropbid.auction.dto.CreateAuctionRequest;
import com.dropbid.auction.dto.PlaceBidRequest;
import com.dropbid.auction.model.Auction;
import com.dropbid.auction.service.AuctionService;
import com.dropbid.shared.security.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping
public class AuctionController {

    private final AuctionService service;

    public AuctionController(AuctionService service) {
        this.service = service;
    }

    /** POST /auctions — seller creates an auction */
    @PostMapping("/auctions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SELLER')")
    public Auction createAuction(
            @Valid @RequestBody CreateAuctionRequest req,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return service.createAuction(principal.userId(), req.shopId(), req.itemId(),
                req.startingBid(), req.maxPrice(), req.startTime(), req.endTime(),
                req.quantity() != null ? req.quantity() : 1L);
    }

    /** GET /auctions/{id} */
    @GetMapping("/auctions/{id}")
    @PreAuthorize("isAuthenticated()")
    public Auction getAuction(@PathVariable String id) {
        return service.getAuction(id);
    }

    /** GET /auctions?status=OPEN */
    @GetMapping("/auctions")
    @PreAuthorize("isAuthenticated()")
    public List<Auction> listAuctions(@RequestParam(defaultValue = "OPEN") String status) {
        return service.listAuctions(status);
    }

    /** PUT /auctions/{id}/bid — buyer places a bid */
    @PutMapping("/auctions/{id}/bid")
    @PreAuthorize("hasRole('BUYER')")
    public BidResult placeBid(
            @PathVariable String id,
            @Valid @RequestBody PlaceBidRequest req,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return service.placeBid(id, req.amount(), principal.userId());
    }

}
