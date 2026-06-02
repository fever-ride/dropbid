package com.dropbid.auction.bid.controller;

import com.dropbid.auction.bid.model.Bid;
import com.dropbid.auction.bid.repository.BidStore;
import com.dropbid.auction.config.SecurityConfig;
import com.dropbid.auction.model.Auction;
import com.dropbid.auction.repository.AuctionStore;
import com.dropbid.shared.security.JwtUtil;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Slice test for {@link BidController}.
 *
 * <p>Authentication is driven through the real {@link com.dropbid.shared.security.JwtAuthFilter}
 * by mocking {@link JwtUtil#validateToken} — the same approach used in
 * {@link com.dropbid.auction.controller.AuctionControllerTest}.  This correctly exercises
 * both the HTTP security rules ({@code anyRequest().authenticated()}) and method-level
 * {@code @PreAuthorize} annotations without fighting the {@code STATELESS}
 * security-context repository.
 */
@WebMvcTest(BidController.class)
@Import(SecurityConfig.class)   // same reason as AuctionControllerTest — forces JwtAuthFilter +
                                // @EnableMethodSecurity into the slice context.
class BidControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean BidStore            bidStore;
    @MockBean AuctionStore        auctionStore;
    @MockBean StringRedisTemplate redis;
    @MockBean JwtUtil             jwtUtil;

    @SuppressWarnings("unchecked")
    ZSetOperations<String, String> zSetOps = mock(ZSetOperations.class);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // "buyer-token" → principal buyer-1 / role BUYER
        Claims buyerClaims = mock(Claims.class);
        when(buyerClaims.getSubject()).thenReturn("buyer-1");
        when(buyerClaims.get("role", String.class)).thenReturn("BUYER");
        when(jwtUtil.validateToken("buyer-token")).thenReturn(buyerClaims);

        // "buyer2-token" → principal buyer-2 / role BUYER
        Claims buyer2Claims = mock(Claims.class);
        when(buyer2Claims.getSubject()).thenReturn("buyer-2");
        when(buyer2Claims.get("role", String.class)).thenReturn("BUYER");
        when(jwtUtil.validateToken("buyer2-token")).thenReturn(buyer2Claims);

        // "buyer3-token" → principal buyer-3 / role BUYER
        Claims buyer3Claims = mock(Claims.class);
        when(buyer3Claims.getSubject()).thenReturn("buyer-3");
        when(buyer3Claims.get("role", String.class)).thenReturn("BUYER");
        when(jwtUtil.validateToken("buyer3-token")).thenReturn(buyer3Claims);

        // "admin-token" → principal admin-1 / role ADMIN
        Claims adminClaims = mock(Claims.class);
        when(adminClaims.getSubject()).thenReturn("admin-1");
        when(adminClaims.get("role", String.class)).thenReturn("ADMIN");
        when(jwtUtil.validateToken("admin-token")).thenReturn(adminClaims);

        when(redis.opsForZSet()).thenReturn(zSetOps);
        // Default: no live winners in Redis — fall back to DynamoDB snapshot
        when(zSetOps.rangeWithScores(anyString(), anyLong(), anyLong()))
                .thenReturn(Set.of());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Bid bid(String bidId, String auctionId, String bidderId, long amount) {
        Bid b = new Bid();
        b.setBidId(bidId);
        b.setAuctionId(auctionId);
        b.setBidderId(bidderId);
        b.setAmount(amount);
        b.setCreatedAt(Instant.now().toString());
        return b;
    }

    // ── GET /auctions/{auctionId}/bids ────────────────────────────────────────

    @Test
    void getAuctionBids_returnsListWithDerivedStatus() throws Exception {
        Bid b = bid("bid-1", "a1", "buyer-1", 300L);
        when(bidStore.findByAuctionId("a1")).thenReturn(List.of(b));

        // DynamoDB snapshot shows buyer-1 as winner at 300
        Auction auction = new Auction();
        auction.setAuctionId("a1");
        auction.setWinners(Map.of("buyer-1", 300L));
        when(auctionStore.findByIdOrNull("a1")).thenReturn(auction);

        mockMvc.perform(get("/auctions/a1/bids")
                        .header("Authorization", "Bearer buyer-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].bidId").value("bid-1"))
                .andExpect(jsonPath("$[0].status").value("WINNING"));
    }

    @Test
    void getAuctionBids_bidderNotInWinners_returnsOutbid() throws Exception {
        Bid b = bid("bid-2", "a2", "buyer-2", 100L);
        when(bidStore.findByAuctionId("a2")).thenReturn(List.of(b));
        // No winners in Redis or DynamoDB → bid is OUTBID
        when(auctionStore.findByIdOrNull("a2")).thenReturn(null);

        mockMvc.perform(get("/auctions/a2/bids")
                        .header("Authorization", "Bearer buyer2-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("OUTBID"));
    }

    @Test
    void getAuctionBids_liveRedisWinners_takePrecedenceOverDynamo() throws Exception {
        Bid b = bid("bid-3", "a3", "buyer-3", 500L);
        when(bidStore.findByAuctionId("a3")).thenReturn(List.of(b));

        // Live Redis ZSET has buyer-3 at 500
        @SuppressWarnings("unchecked")
        ZSetOperations.TypedTuple<String> tuple = mock(ZSetOperations.TypedTuple.class);
        when(tuple.getValue()).thenReturn("buyer-3");
        when(tuple.getScore()).thenReturn(500.0);
        when(zSetOps.rangeWithScores("auction:a3:winners", 0, -1))
                .thenReturn(Set.of(tuple));

        mockMvc.perform(get("/auctions/a3/bids")
                        .header("Authorization", "Bearer buyer3-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("WINNING"));
    }

    @Test
    void getAuctionBids_unauthenticated_returns401() throws Exception {
        // No Authorization header → JwtAuthFilter skips → anonymous → 401
        mockMvc.perform(get("/auctions/a1/bids"))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /bids/me ──────────────────────────────────────────────────────────

    @Test
    void getMyBids_returnsCallerBids() throws Exception {
        Bid b = bid("bid-4", "a4", "buyer-1", 200L);
        when(bidStore.findByBidderId("buyer-1")).thenReturn(List.of(b));
        when(auctionStore.findByIdOrNull("a4")).thenReturn(null);

        mockMvc.perform(get("/bids/me")
                        .header("Authorization", "Bearer buyer-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].bidderId").value("buyer-1"))
                .andExpect(jsonPath("$[0].auctionId").value("a4"));
    }

    // ── GET /bids/user/{userId} ───────────────────────────────────────────────

    @Test
    void getUserBids_asAdmin_returnsTargetUserBids() throws Exception {
        Bid b = bid("bid-5", "a5", "buyer-9", 999L);
        when(bidStore.findByBidderId("buyer-9")).thenReturn(List.of(b));
        when(auctionStore.findByIdOrNull("a5")).thenReturn(null);

        mockMvc.perform(get("/bids/user/buyer-9")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].bidderId").value("buyer-9"));
    }

    @Test
    void getUserBids_asBuyer_returns403() throws Exception {
        // @PreAuthorize("hasRole('ADMIN')") → BUYER auth → 403
        mockMvc.perform(get("/bids/user/some-user")
                        .header("Authorization", "Bearer buyer-token"))
                .andExpect(status().isForbidden());
    }
}
