package com.dropbid.payment.controller;

import com.dropbid.payment.config.SecurityConfig;
import com.dropbid.payment.model.Payment;
import com.dropbid.payment.model.PaymentStatus;
import com.dropbid.payment.service.PaymentService;
import com.dropbid.shared.security.JwtUtil;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Slice test for {@link PaymentController}.
 *
 * <p>All endpoints require authentication ({@code isAuthenticated()}).  The
 * {@code /payments/me} endpoint additionally exercises principal injection:
 * the principal's {@code userId} is forwarded to {@link PaymentService#getUserPayments}.
 */
@WebMvcTest(PaymentController.class)
@Import(SecurityConfig.class)
class PaymentControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean PaymentService service;
    @MockBean JwtUtil        jwtUtil;

    @BeforeEach
    void setUp() {
        // "user-token" → principal user-1 / role BUYER
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("user-1");
        when(claims.get("role", String.class)).thenReturn("BUYER");
        when(jwtUtil.validateToken("user-token")).thenReturn(claims);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    static Payment payment(String id, String auctionId, String userId) {
        Payment p = new Payment();
        p.setId(id);
        p.setAuctionId(auctionId);
        p.setUserId(userId);
        p.setAmount(500L);
        p.setStatus(PaymentStatus.PENDING);
        p.setCreatedAt(Instant.parse("2024-01-01T00:00:00Z"));
        p.setUpdatedAt(Instant.parse("2024-01-01T00:00:00Z"));
        return p;
    }

    // ── GET /payments/{id} ────────────────────────────────────────────────────

    @Test
    void getPayment_authenticated_returns200WithPaymentJson() throws Exception {
        when(service.getPayment("pay-1")).thenReturn(payment("pay-1", "auction-1", "user-1"));

        mockMvc.perform(get("/payments/pay-1")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("pay-1"))
                .andExpect(jsonPath("$.auctionId").value("auction-1"))
                .andExpect(jsonPath("$.amount").value(500));
    }

    @Test
    void getPayment_notFound_returns404() throws Exception {
        when(service.getPayment("missing"))
                .thenThrow(new ResponseStatusException(NOT_FOUND, "payment not found"));

        mockMvc.perform(get("/payments/missing")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getPayment_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/payments/pay-1"))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /payments/auction/{auctionId} ─────────────────────────────────────

    @Test
    void getByAuction_authenticated_returnsPaymentList() throws Exception {
        when(service.getByAuctionId("auction-1"))
                .thenReturn(List.of(payment("pay-1", "auction-1", "user-1")));

        mockMvc.perform(get("/payments/auction/auction-1")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("pay-1"))
                .andExpect(jsonPath("$[0].auctionId").value("auction-1"));
    }

    @Test
    void getByAuction_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/payments/auction/auction-1"))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /payments/user/{userId} ────────────────────────────────────────────

    @Test
    void getUserPayments_authenticated_returnsList() throws Exception {
        when(service.getUserPayments("user-1"))
                .thenReturn(List.of(payment("pay-1", "auction-1", "user-1")));

        mockMvc.perform(get("/payments/user/user-1")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value("user-1"));
    }

    @Test
    void getUserPayments_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/payments/user/user-1"))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /payments/me ──────────────────────────────────────────────────────

    @Test
    void myPayments_authenticated_delegatesToPrincipalUserId() throws Exception {
        // JwtAuthFilter resolves principal.userId() = "user-1" from the mock Claims;
        // the controller calls service.getUserPayments(principal.userId()) with that value.
        when(service.getUserPayments("user-1"))
                .thenReturn(List.of(payment("pay-1", "auction-1", "user-1")));

        mockMvc.perform(get("/payments/me")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("pay-1"));

        verify(service).getUserPayments("user-1");
    }

    @Test
    void myPayments_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/payments/me"))
                .andExpect(status().isUnauthorized());
    }
}
