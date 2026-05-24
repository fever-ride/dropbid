package com.dropbid.auction.bid.controller;

import com.dropbid.auction.bid.model.Bid;
import com.dropbid.auction.bid.repository.BidStore;
import com.dropbid.shared.security.UserPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class BidController {

    private final BidStore bidStore;

    public BidController(BidStore bidStore) {
        this.bidStore = bidStore;
    }

    /** GET /auctions/{auctionId}/bids — full bid history for an auction */
    @GetMapping("/auctions/{auctionId}/bids")
    @PreAuthorize("isAuthenticated()")
    public List<Bid> getAuctionBids(@PathVariable String auctionId) {
        return bidStore.findByAuctionId(auctionId);
    }

    /** GET /bids/me — caller's own bid history across all auctions */
    @GetMapping("/bids/me")
    @PreAuthorize("isAuthenticated()")
    public List<Bid> getMyBids(@AuthenticationPrincipal UserPrincipal principal) {
        return bidStore.findByBidderId(principal.userId());
    }

    /** GET /bids/user/{userId} — any user's bid history (admin only) */
    @GetMapping("/bids/user/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Bid> getUserBids(@PathVariable String userId) {
        return bidStore.findByBidderId(userId);
    }
}
