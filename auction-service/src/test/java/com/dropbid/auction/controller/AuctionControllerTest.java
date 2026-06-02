package com.dropbid.auction.controller;

import com.dropbid.auction.concurrency.BidResult;
import com.dropbid.auction.config.SecurityConfig;
import com.dropbid.auction.dto.CreateAuctionRequest;
import com.dropbid.auction.dto.PlaceBidRequest;
import com.dropbid.auction.model.Auction;
import com.dropbid.auction.service.AuctionService;
import com.dropbid.shared.security.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Slice test for {@link AuctionController}.
 *
 * <p>Authentication is driven through the real {@link com.dropbid.shared.security.JwtAuthFilter}
 * by mocking {@link JwtUtil#validateToken} to return pre-built {@link Claims} objects.
 * This keeps the test realistic (the whole security pipeline is exercised) and avoids
 * the {@link org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors#authentication}
 * post-processor, which does not work with {@code SessionCreationPolicy.STATELESS} because
 * the security-context repository is a {@code NullSecurityContextRepository} in that mode.
 */
@WebMvcTest(AuctionController.class)
@Import(SecurityConfig.class)   // @WebMvcTest type-filter excludes @Configuration classes that are not
                                // in the web layer; @Import ensures our SecurityFilterChain (with
                                // JwtAuthFilter and @EnableMethodSecurity) is loaded in the slice.
class AuctionControllerTest {

    @Autowired MockMvc        mockMvc;
    @Autowired ObjectMapper   mapper;

    @MockBean AuctionService service;
    @MockBean JwtUtil        jwtUtil;

    @BeforeEach
    void setUp() {
        // "seller-token" → principal seller-1 / role SELLER
        Claims sellerClaims = mock(Claims.class);
        when(sellerClaims.getSubject()).thenReturn("seller-1");
        when(sellerClaims.get("role", String.class)).thenReturn("SELLER");
        when(jwtUtil.validateToken("seller-token")).thenReturn(sellerClaims);

        // "buyer-token"  → principal buyer-1 / role BUYER
        Claims buyerClaims = mock(Claims.class);
        when(buyerClaims.getSubject()).thenReturn("buyer-1");
        when(buyerClaims.get("role", String.class)).thenReturn("BUYER");
        when(jwtUtil.validateToken("buyer-token")).thenReturn(buyerClaims);
    }

    // ── GET /auctions/{id} ────────────────────────────────────────────────────

    @Test
    void getAuction_found_returns200() throws Exception {
        Auction a = new Auction();
        a.setAuctionId("a1");
        a.setStatus("OPEN");
        when(service.getAuction("a1")).thenReturn(a);

        mockMvc.perform(get("/auctions/a1")
                        .header("Authorization", "Bearer seller-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auctionId").value("a1"))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void getAuction_notFound_returns404() throws Exception {
        when(service.getAuction("missing"))
                .thenThrow(new ResponseStatusException(NOT_FOUND, "auction not found"));

        mockMvc.perform(get("/auctions/missing")
                        .header("Authorization", "Bearer buyer-token"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getAuction_unauthenticated_returns401() throws Exception {
        // No Authorization header → JwtAuthFilter skips → anonymous → 401
        mockMvc.perform(get("/auctions/a1"))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /auctions ─────────────────────────────────────────────────────────

    @Test
    void listAuctions_defaultsToOpenStatus_returnsJsonArray() throws Exception {
        Auction a = new Auction();
        a.setAuctionId("a2");
        a.setStatus("OPEN");
        when(service.listAuctions("OPEN")).thenReturn(List.of(a));

        mockMvc.perform(get("/auctions")
                        .header("Authorization", "Bearer buyer-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].auctionId").value("a2"));
    }

    @Test
    void listAuctions_withStatusFilter_forwardedToService() throws Exception {
        when(service.listAuctions("PENDING")).thenReturn(List.of());

        mockMvc.perform(get("/auctions")
                        .param("status", "PENDING")
                        .header("Authorization", "Bearer seller-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // ── POST /auctions ────────────────────────────────────────────────────────

    @Test
    void createAuction_validRequest_asSeller_returns201() throws Exception {
        CreateAuctionRequest req = new CreateAuctionRequest(
                "item-1", "shop-1", 100L, null,
                null, "2099-12-31T23:59:59Z", 1L);

        Auction created = new Auction();
        created.setAuctionId("new-auction");
        created.setStatus("OPEN");
        when(service.createAuction(eq("seller-1"), eq("shop-1"), eq("item-1"),
                eq(100L), isNull(), isNull(), eq("2099-12-31T23:59:59Z"), eq(1L)))
                .thenReturn(created);

        mockMvc.perform(post("/auctions")
                        .header("Authorization", "Bearer seller-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.auctionId").value("new-auction"));
    }

    @Test
    void createAuction_asBuyer_returns403() throws Exception {
        CreateAuctionRequest req = new CreateAuctionRequest(
                "item-1", "shop-1", 100L, null,
                null, "2099-12-31T23:59:59Z", 1L);

        mockMvc.perform(post("/auctions")
                        .header("Authorization", "Bearer buyer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    void createAuction_missingRequiredField_returns400() throws Exception {
        // itemId is @NotBlank — omitting it must fail Bean Validation before reaching service
        String badJson = "{\"shopId\":\"shop-1\",\"startingBid\":100,\"endTime\":\"2099-12-31T23:59:59Z\"}";

        mockMvc.perform(post("/auctions")
                        .header("Authorization", "Bearer seller-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badJson))
                .andExpect(status().isBadRequest());
    }

    // ── PUT /auctions/{id}/bid ────────────────────────────────────────────────

    @Test
    void placeBid_asBuyer_returns200WithResult() throws Exception {
        BidResult result = new BidResult("a1", "buyer-1", 500L,
                1L, 1L, null, 0L, 500L, "buyer-1", Map.of("buyer-1", 500L));
        when(service.placeBid("a1", 500L, "buyer-1")).thenReturn(result);

        mockMvc.perform(put("/auctions/a1/bid")
                        .header("Authorization", "Bearer buyer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new PlaceBidRequest(500L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(500));
    }

    @Test
    void placeBid_asSeller_returns403() throws Exception {
        mockMvc.perform(put("/auctions/a1/bid")
                        .header("Authorization", "Bearer seller-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new PlaceBidRequest(500L))))
                .andExpect(status().isForbidden());
    }

    @Test
    void placeBid_amountBelowMin_returns400() throws Exception {
        // @Min(1) on PlaceBidRequest.amount
        mockMvc.perform(put("/auctions/a1/bid")
                        .header("Authorization", "Bearer buyer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":0}"))
                .andExpect(status().isBadRequest());
    }
}
