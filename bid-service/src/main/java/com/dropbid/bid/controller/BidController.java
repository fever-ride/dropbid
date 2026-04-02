package com.dropbid.bid.controller;

import com.dropbid.bid.model.Bid;
import com.dropbid.bid.service.BidService;
import com.dropbid.shared.security.UserPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/bids")
public class BidController {

    private final BidService service;

    public BidController(BidService service) {
        this.service = service;
    }

    /** GET /bids/auction/{auctionId} — all bids for an auction (newest first) */
    @GetMapping("/auction/{auctionId}")
    @PreAuthorize("isAuthenticated()")
    public List<Bid> getAuctionBids(@PathVariable String auctionId) {
        return service.getAuctionBids(auctionId);
    }

    /** GET /bids/user/{userId} — all bids by a user (admin only) */
    @GetMapping("/user/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Bid> getUserBids(@PathVariable String userId) {
        return service.getUserBids(userId);
    }

    /** GET /bids/me — bids by the authenticated user */
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public List<Bid> myBids(@AuthenticationPrincipal UserPrincipal principal) {
        return service.getUserBids(principal.userId());
    }
}
