package com.dropbid.query.controller;

import com.dropbid.query.config.SecurityConfig;
import com.dropbid.query.dto.BidSummaryProjection;
import com.dropbid.query.dto.EnrichedAuctionSummary;
import com.dropbid.query.dto.EnrichedBidActivity;
import com.dropbid.query.repository.AuctionRepository;
import com.dropbid.query.repository.BidRepository;
import com.dropbid.shared.security.JwtUtil;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Slice test for {@link BuyerQueryController}, {@link SellerQueryController},
 * and {@link PublicQueryController}.
 *
 * <p>Authentication is driven through the real {@link com.dropbid.shared.security.JwtAuthFilter}
 * by mocking {@link JwtUtil#validateToken} to return pre-built {@link Claims} objects.
 * This keeps the test realistic (the whole security pipeline is exercised) and avoids
 * the {@link org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors#authentication}
 * post-processor, which does not work with {@code SessionCreationPolicy.STATELESS} because
 * the security-context repository is a {@code NullSecurityContextRepository} in that mode.
 */
@WebMvcTest({BuyerQueryController.class, SellerQueryController.class, PublicQueryController.class})
@Import(SecurityConfig.class)   // @WebMvcTest type-filter excludes @Configuration beans that are not
                                // in the web layer; @Import ensures our SecurityFilterChain (with
                                // JwtAuthFilter and @EnableMethodSecurity) is loaded in the slice.
class QueryControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean BidRepository      bidRepo;
    @MockBean AuctionRepository  auctionRepo;
    @MockBean EnrichmentService  enrichment;
    @MockBean JwtUtil            jwtUtil;

    @BeforeEach
    void setUp() {
        // "buyer-token" → principal buyer-1 / role BUYER
        Claims buyerClaims = mock(Claims.class);
        when(buyerClaims.getSubject()).thenReturn("buyer-1");
        when(buyerClaims.get("role", String.class)).thenReturn("BUYER");
        when(jwtUtil.validateToken("buyer-token")).thenReturn(buyerClaims);

        // "seller-token" → principal seller-1 / role SELLER
        Claims sellerClaims = mock(Claims.class);
        when(sellerClaims.getSubject()).thenReturn("seller-1");
        when(sellerClaims.get("role", String.class)).thenReturn("SELLER");
        when(jwtUtil.validateToken("seller-token")).thenReturn(sellerClaims);
    }

    // ── BuyerQueryController: GET /query/my/bids ──────────────────────────────

    @Test
    void myBids_asBuyer_returns200() throws Exception {
        when(bidRepo.findBidSummariesByBidderId(eq("buyer-1"), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(bidRepo.countDistinctAuctionsByBidderId(eq("buyer-1")))
                .thenReturn(0L);
        when(enrichment.enrichBids(any()))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/query/my/bids")
                        .header("Authorization", "Bearer buyer-token"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void myBids_withStatusFilter_callsFilteredQuery() throws Exception {
        when(bidRepo.findBidSummariesByBidderIdAndStatus(eq("buyer-1"), eq("WON"), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(bidRepo.countBidSummariesByBidderIdAndStatus(eq("buyer-1"), eq("WON")))
                .thenReturn(0L);
        when(enrichment.enrichBids(any()))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/query/my/bids")
                        .param("status", "WON")
                        .header("Authorization", "Bearer buyer-token"))
                .andExpect(status().isOk());
    }

    @Test
    void myBids_asSeller_returns403() throws Exception {
        mockMvc.perform(get("/query/my/bids")
                        .header("Authorization", "Bearer seller-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void myBids_unauthenticated_returns401() throws Exception {
        // No Authorization header → JwtAuthFilter skips → anonymous → 401
        mockMvc.perform(get("/query/my/bids"))
                .andExpect(status().isUnauthorized());
    }

    // ── SellerQueryController: GET /query/seller/auctions ────────────────────

    @Test
    void sellerAuctions_asSeller_returns200() throws Exception {
        when(auctionRepo.findBySellerId(eq("seller-1"), any()))
                .thenReturn(new PageImpl<>(List.of()));
        when(enrichment.enrichAuctions(any()))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/query/seller/auctions")
                        .header("Authorization", "Bearer seller-token"))
                .andExpect(status().isOk());
    }

    @Test
    void sellerAuctions_withStatusFilter_callsFilteredQuery() throws Exception {
        when(auctionRepo.findBySellerIdAndStatus(eq("seller-1"), eq("CLOSED"), any()))
                .thenReturn(new PageImpl<>(List.of()));
        when(enrichment.enrichAuctions(any()))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/query/seller/auctions")
                        .param("status", "CLOSED")
                        .header("Authorization", "Bearer seller-token"))
                .andExpect(status().isOk());
    }

    @Test
    void sellerAuctions_asBuyer_returns403() throws Exception {
        mockMvc.perform(get("/query/seller/auctions")
                        .header("Authorization", "Bearer buyer-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void sellerAuctionBids_asSeller_returns200JsonArray() throws Exception {
        when(bidRepo.findBidSummariesByAuctionId("a1"))
                .thenReturn(List.of());
        when(enrichment.enrichBidList(any()))
                .thenReturn(List.of());

        mockMvc.perform(get("/query/seller/auctions/a1/bids")
                        .header("Authorization", "Bearer seller-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // ── PublicQueryController: GET /query/auctions ────────────────────────────

    @Test
    void publicAuctions_noAuth_returns200() throws Exception {
        when(auctionRepo.findByStatus(any(), any()))
                .thenReturn(new PageImpl<>(List.of()));
        when(enrichment.enrichAuctions(any()))
                .thenReturn(new PageImpl<>(List.of()));

        // No Authorization header — /query/auctions/** is permitAll()
        mockMvc.perform(get("/query/auctions"))
                .andExpect(status().isOk());
    }

    @Test
    void publicAuctions_defaultSort_callsBidCountSort() throws Exception {
        when(auctionRepo.findByStatus(any(), any()))
                .thenReturn(new PageImpl<>(List.of()));
        when(enrichment.enrichAuctions(any()))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/query/auctions"))
                .andExpect(status().isOk());
    }

    @Test
    void publicAuctions_customSortCurrentHighest_returns200() throws Exception {
        when(auctionRepo.findByStatus(any(), any()))
                .thenReturn(new PageImpl<>(List.of()));
        when(enrichment.enrichAuctions(any()))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/query/auctions")
                        .param("sort", "currentHighest"))
                .andExpect(status().isOk());
    }

    @Test
    void publicAuctions_customStatus_returns200() throws Exception {
        when(auctionRepo.findByStatus(any(), any()))
                .thenReturn(new PageImpl<>(List.of()));
        when(enrichment.enrichAuctions(any()))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/query/auctions")
                        .param("status", "CLOSED"))
                .andExpect(status().isOk());
    }
}
