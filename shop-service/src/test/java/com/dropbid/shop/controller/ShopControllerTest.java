package com.dropbid.shop.controller;

import com.dropbid.shared.security.JwtUtil;
import com.dropbid.shop.config.SecurityConfig;
import com.dropbid.shop.dto.CreateItemRequest;
import com.dropbid.shop.dto.CreateShopRequest;
import com.dropbid.shop.model.CollectibleItem;
import com.dropbid.shop.model.Condition;
import com.dropbid.shop.model.SellerProfile;
import com.dropbid.shop.service.ShopService;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Slice test for {@link ShopController}.
 *
 * <p>Auth is driven through the real {@link com.dropbid.shared.security.JwtAuthFilter}
 * by mocking {@link JwtUtil#validateToken}.  Role-restricted endpoints
 * ({@code hasRole('SELLER')}) are tested for correct 403 when a BUYER token is presented.
 */
@WebMvcTest(ShopController.class)
@Import(SecurityConfig.class)
class ShopControllerTest {

    @Autowired MockMvc      mockMvc;
    @Autowired ObjectMapper mapper;

    @MockBean ShopService service;
    @MockBean JwtUtil     jwtUtil;

    @BeforeEach
    void setUp() {
        // "seller-token" → principal seller-1 / role SELLER
        Claims sellerClaims = mock(Claims.class);
        when(sellerClaims.getSubject()).thenReturn("seller-1");
        when(sellerClaims.get("role", String.class)).thenReturn("SELLER");
        when(jwtUtil.validateToken("seller-token")).thenReturn(sellerClaims);

        // "buyer-token" → principal buyer-1 / role BUYER
        Claims buyerClaims = mock(Claims.class);
        when(buyerClaims.getSubject()).thenReturn("buyer-1");
        when(buyerClaims.get("role", String.class)).thenReturn("BUYER");
        when(jwtUtil.validateToken("buyer-token")).thenReturn(buyerClaims);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    static SellerProfile profile(String id, String ownerId) {
        SellerProfile p = new SellerProfile();
        p.setId(id);
        p.setOwnerId(ownerId);
        p.setName("Test Shop");
        p.setBio("A test seller");
        p.setRating(BigDecimal.ZERO);
        p.setCreatedAt(Instant.parse("2024-01-01T00:00:00Z"));
        return p;
    }

    static CollectibleItem item(String id, String shopId) {
        CollectibleItem i = new CollectibleItem();
        i.setId(id);
        i.setShopId(shopId);
        i.setTitle("Pikachu Figure");
        i.setCondition(Condition.NEW);
        i.setOriginalRetailPrice(2000L);
        i.setEstimatedMarketValue(3500L);
        i.setCreatedAt(Instant.parse("2024-01-01T00:00:00Z"));
        return i;
    }

    // ── POST /shops ───────────────────────────────────────────────────────────

    @Test
    void createShop_asSeller_returns201WithShopResponse() throws Exception {
        CreateShopRequest req = new CreateShopRequest("My Shop", "Great collector");
        when(service.createShop(eq("seller-1"), any())).thenReturn(profile("shop-1", "seller-1"));

        mockMvc.perform(post("/shops")
                        .header("Authorization", "Bearer seller-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("shop-1"))
                .andExpect(jsonPath("$.ownerId").value("seller-1"))
                .andExpect(jsonPath("$.name").value("Test Shop"));
    }

    @Test
    void createShop_asBuyer_returns403() throws Exception {
        // hasRole('SELLER') — BUYER must be rejected
        CreateShopRequest req = new CreateShopRequest("My Shop", "Bio");

        mockMvc.perform(post("/shops")
                        .header("Authorization", "Bearer buyer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    void createShop_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/shops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"My Shop\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createShop_missingName_returns400() throws Exception {
        // name is @NotBlank — omitting it must fail Bean Validation before reaching service
        String badJson = "{\"bio\":\"some bio\"}";

        mockMvc.perform(post("/shops")
                        .header("Authorization", "Bearer seller-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badJson))
                .andExpect(status().isBadRequest());
    }

    // ── GET /shops/{id} ───────────────────────────────────────────────────────

    @Test
    void getShop_authenticated_returns200WithShopJson() throws Exception {
        when(service.getShop("shop-1")).thenReturn(profile("shop-1", "seller-1"));

        mockMvc.perform(get("/shops/shop-1")
                        .header("Authorization", "Bearer buyer-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("shop-1"))
                .andExpect(jsonPath("$.ownerId").value("seller-1"));
    }

    @Test
    void getShop_notFound_returns404() throws Exception {
        when(service.getShop("missing"))
                .thenThrow(new ResponseStatusException(NOT_FOUND, "shop not found"));

        mockMvc.perform(get("/shops/missing")
                        .header("Authorization", "Bearer buyer-token"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getShop_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/shops/shop-1"))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /shops/owner/{ownerId} ────────────────────────────────────────────

    @Test
    void getShopByOwner_authenticated_returns200() throws Exception {
        when(service.getShopByOwner("seller-1")).thenReturn(profile("shop-1", "seller-1"));

        mockMvc.perform(get("/shops/owner/seller-1")
                        .header("Authorization", "Bearer buyer-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").value("seller-1"));
    }

    @Test
    void getShopByOwner_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/shops/owner/seller-1"))
                .andExpect(status().isUnauthorized());
    }

    // ── POST /shops/{shopId}/items ────────────────────────────────────────────

    @Test
    void addItem_asSeller_returns201WithItemJson() throws Exception {
        CreateItemRequest req = new CreateItemRequest(
                "Pikachu Figure", "Classic", "Gen 1", "1st",
                "NEW", 2000L, 3500L, "http://img.test/pikachu.jpg");
        when(service.addItem(eq("shop-1"), eq("seller-1"), any()))
                .thenReturn(item("item-1", "shop-1"));

        mockMvc.perform(post("/shops/shop-1/items")
                        .header("Authorization", "Bearer seller-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("item-1"))
                .andExpect(jsonPath("$.title").value("Pikachu Figure"))
                .andExpect(jsonPath("$.condition").value("NEW"));
    }

    @Test
    void addItem_asBuyer_returns403() throws Exception {
        // hasRole('SELLER') — BUYER must be rejected before reaching service
        CreateItemRequest req = new CreateItemRequest(
                "Pikachu Figure", null, null, null,
                "NEW", 2000L, 3500L, null);

        mockMvc.perform(post("/shops/shop-1/items")
                        .header("Authorization", "Bearer buyer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    void addItem_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/shops/shop-1/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Figure\",\"condition\":\"NEW\",\"originalRetailPrice\":100,\"estimatedMarketValue\":200}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void addItem_invalidCondition_returns400() throws Exception {
        // condition must match NEW|LIKE_NEW|GOOD|FAIR — "JUNK" must fail Bean Validation
        String badJson = "{\"title\":\"Pikachu\",\"condition\":\"JUNK\",\"originalRetailPrice\":100,\"estimatedMarketValue\":150}";

        mockMvc.perform(post("/shops/shop-1/items")
                        .header("Authorization", "Bearer seller-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    void addItem_missingTitle_returns400() throws Exception {
        // title is @NotBlank
        String badJson = "{\"condition\":\"NEW\",\"originalRetailPrice\":100,\"estimatedMarketValue\":200}";

        mockMvc.perform(post("/shops/shop-1/items")
                        .header("Authorization", "Bearer seller-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badJson))
                .andExpect(status().isBadRequest());
    }

    // ── GET /shops/{shopId}/items ─────────────────────────────────────────────

    @Test
    void listItems_authenticated_returnsJsonArray() throws Exception {
        when(service.listItems("shop-1")).thenReturn(List.of(item("item-1", "shop-1")));

        mockMvc.perform(get("/shops/shop-1/items")
                        .header("Authorization", "Bearer buyer-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("item-1"))
                .andExpect(jsonPath("$[0].shopId").value("shop-1"));
    }

    @Test
    void listItems_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/shops/shop-1/items"))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /items/{id} ───────────────────────────────────────────────────────

    @Test
    void getItem_authenticated_returns200() throws Exception {
        when(service.getItem("item-1")).thenReturn(item("item-1", "shop-1"));

        mockMvc.perform(get("/items/item-1")
                        .header("Authorization", "Bearer buyer-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("item-1"));
    }

    @Test
    void getItem_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/items/item-1"))
                .andExpect(status().isUnauthorized());
    }
}
