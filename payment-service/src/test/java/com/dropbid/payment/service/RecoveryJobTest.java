package com.dropbid.payment.service;

import com.dropbid.payment.model.Payment;
import com.dropbid.payment.model.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecoveryJobTest {

    @Mock PaymentService service;

    RecoveryJob job;

    @BeforeEach
    void setUp() {
        job = new RecoveryJob(service);
        // Replicate the default @Value values that Spring would inject
        ReflectionTestUtils.setField(job, "stuckThresholdMinutes", 5);
        ReflectionTestUtils.setField(job, "maxRetries", 1);
    }

    static Payment stuckPayment(String id, int retryCount) {
        Payment p = new Payment();
        p.setId(id);
        p.setAuctionId("a-1");
        p.setUserId("user-1");
        p.setAmount(500L);
        p.setStatus(PaymentStatus.PROCESSING);
        p.setRetryCount(retryCount);
        return p;
    }

    // ── 1. No stuck payments → early return, nothing called ───────────────────

    @Test
    void recoverStuckPayments_noStuckPayments_doesNothing() {
        when(service.getStuckPayments(any(Instant.class))).thenReturn(List.of());

        job.recoverStuckPayments();

        verify(service, never()).abandonPayment(any());
        verify(service, never()).incrementRetryCount(any());
        verify(service, never()).processPayment(any());
    }

    // ── 2. retryCount < maxRetries → increment + process ─────────────────────

    @Test
    void recoverStuckPayments_belowMaxRetries_incrementsAndProcesses() {
        // maxRetries=1, retryCount=0  → 0 < 1 → retry
        Payment p = stuckPayment("pay-1", 0);
        when(service.getStuckPayments(any(Instant.class))).thenReturn(List.of(p));

        job.recoverStuckPayments();

        verify(service).incrementRetryCount("pay-1");
        verify(service).processPayment("pay-1");
        verify(service, never()).abandonPayment(any());
    }

    // ── 3. retryCount >= maxRetries → abandon ─────────────────────────────────

    @Test
    void recoverStuckPayments_atMaxRetries_abandons() {
        // maxRetries=1, retryCount=1 → 1 >= 1 → abandon
        Payment p = stuckPayment("pay-1", 1);
        when(service.getStuckPayments(any(Instant.class))).thenReturn(List.of(p));

        job.recoverStuckPayments();

        verify(service).abandonPayment("pay-1");
        verify(service, never()).incrementRetryCount(any());
        verify(service, never()).processPayment(any());
    }

    @Test
    void recoverStuckPayments_exceedsMaxRetries_abandons() {
        // retryCount > maxRetries should also abandon
        Payment p = stuckPayment("pay-1", 5);
        when(service.getStuckPayments(any(Instant.class))).thenReturn(List.of(p));

        job.recoverStuckPayments();

        verify(service).abandonPayment("pay-1");
    }

    // ── 4. Exception in one payment → loop continues for others ──────────────

    @Test
    void recoverStuckPayments_oneThrows_continuesWithRemainingPayments() {
        Payment failing = stuckPayment("pay-fail", 0);
        Payment ok      = stuckPayment("pay-ok",   0);
        when(service.getStuckPayments(any(Instant.class))).thenReturn(List.of(failing, ok));
        doThrow(new RuntimeException("transient error"))
                .when(service).incrementRetryCount("pay-fail");

        job.recoverStuckPayments(); // must not throw

        // The second payment should still be processed
        verify(service).incrementRetryCount("pay-ok");
        verify(service).processPayment("pay-ok");
    }

    // ── 5. Multiple payments with mixed retry counts ───────────────────────────

    @Test
    void recoverStuckPayments_mixedRetryCount_correctBranchPerPayment() {
        Payment toRetry  = stuckPayment("pay-retry",  0); // 0 < 1 → retry
        Payment toAbandon = stuckPayment("pay-abandon", 1); // 1 >= 1 → abandon
        when(service.getStuckPayments(any(Instant.class)))
                .thenReturn(List.of(toRetry, toAbandon));

        job.recoverStuckPayments();

        verify(service).incrementRetryCount("pay-retry");
        verify(service).processPayment("pay-retry");
        verify(service).abandonPayment("pay-abandon");
    }
}
