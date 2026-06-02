package com.dropbid.payment.service;

import com.dropbid.payment.events.PaymentEventPublisher;
import com.dropbid.payment.model.Payment;
import com.dropbid.payment.model.PaymentStatus;
import com.dropbid.payment.repository.PaymentRepository;
import com.dropbid.shared.events.AuctionClosedEvent;
import com.dropbid.shared.events.PaymentFailedEvent;
import com.dropbid.shared.events.PaymentProcessedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock PaymentRepository      repo;
    @Mock PaymentGateway         gateway;
    @Mock PaymentEventPublisher  publisher;

    PaymentService service;

    @BeforeEach
    void setUp() {
        service = new PaymentService(repo, gateway, publisher);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    static Payment pending(String id, String auctionId, String userId, long amount) {
        Payment p = new Payment();
        p.setId(id);
        p.setAuctionId(auctionId);
        p.setUserId(userId);
        p.setAmount(amount);
        p.setStatus(PaymentStatus.PENDING);
        p.setRetryCount(0);
        return p;
    }

    static AuctionClosedEvent closedEvent(String auctionId, Map<String, Long> winners) {
        return new AuctionClosedEvent(auctionId, winners, "item-1", "shop-1", "2025-01-01T00:00:00Z");
    }

    // ── initiatePayments ───────────────────────────────────────────────────────

    @Test
    void initiatePayments_nullWinners_returnsEmptyList() {
        var result = service.initiatePayments(closedEvent("a-1", null));
        assertThat(result).isEmpty();
        verifyNoInteractions(repo);
    }

    @Test
    void initiatePayments_emptyWinners_returnsEmptyList() {
        var result = service.initiatePayments(closedEvent("a-1", Map.of()));
        assertThat(result).isEmpty();
        verifyNoInteractions(repo);
    }

    @Test
    void initiatePayments_newWinner_createsPendingPayment() {
        when(repo.findByAuctionIdAndUserId("a-1", "user-1")).thenReturn(Optional.empty());
        Payment saved = pending("pay-1", "a-1", "user-1", 500L);
        when(repo.save(any())).thenReturn(saved);

        List<Payment> result = service.initiatePayments(closedEvent("a-1", Map.of("user-1", 500L)));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(result.get(0).getAmount()).isEqualTo(500L);
        verify(repo).save(any(Payment.class));
    }

    @Test
    void initiatePayments_existingPayment_returnsExistingWithoutCreatingNew() {
        Payment existing = pending("pay-already", "a-1", "user-1", 500L);
        when(repo.findByAuctionIdAndUserId("a-1", "user-1")).thenReturn(Optional.of(existing));

        List<Payment> result = service.initiatePayments(closedEvent("a-1", Map.of("user-1", 500L)));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo("pay-already");
        verify(repo, never()).save(any()); // no new save
    }

    @Test
    void initiatePayments_multipleWinners_createsAllNew() {
        when(repo.findByAuctionIdAndUserId(any(), any())).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<Payment> result = service.initiatePayments(
                closedEvent("a-1", Map.of("user-1", 500L, "user-2", 400L)));

        assertThat(result).hasSize(2);
        verify(repo, times(2)).save(any(Payment.class));
    }

    // ── processPayment ─────────────────────────────────────────────────────────

    @Test
    void processPayment_notFound_throws404() {
        when(repo.findById("pay-x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.processPayment("pay-x"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void processPayment_alreadyCompleted_returnsEarlyWithoutGatewayCall() {
        Payment p = pending("pay-1", "a-1", "user-1", 500L);
        p.setStatus(PaymentStatus.COMPLETED);
        when(repo.findById("pay-1")).thenReturn(Optional.of(p));

        service.processPayment("pay-1");

        verifyNoInteractions(gateway);
        verifyNoInteractions(publisher);
    }

    @Test
    void processPayment_alreadyFailed_returnsEarlyWithoutGatewayCall() {
        Payment p = pending("pay-1", "a-1", "user-1", 500L);
        p.setStatus(PaymentStatus.FAILED);
        when(repo.findById("pay-1")).thenReturn(Optional.of(p));

        service.processPayment("pay-1");

        verifyNoInteractions(gateway);
        verifyNoInteractions(publisher);
    }

    @Test
    void processPayment_gatewaySuccess_setsCompletedAndPublishes() {
        Payment p = pending("pay-1", "a-1", "user-1", 500L);
        when(repo.findById("pay-1")).thenReturn(Optional.of(p));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(gateway.charge("pay-1", 500L, "user-1")).thenReturn(PaymentGateway.DECISION_SUCCESS);

        service.processPayment("pay-1");

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(p.getGatewayDecision()).isEqualTo(PaymentGateway.DECISION_SUCCESS);

        ArgumentCaptor<PaymentProcessedEvent> cap = ArgumentCaptor.forClass(PaymentProcessedEvent.class);
        verify(publisher).publishPaymentProcessed(cap.capture());
        assertThat(cap.getValue().paymentId()).isEqualTo("pay-1");
        assertThat(cap.getValue().auctionId()).isEqualTo("a-1");
        assertThat(cap.getValue().amount()).isEqualTo(500L);
    }

    @Test
    void processPayment_gatewayFailure_setsFailedAndPublishes() {
        Payment p = pending("pay-1", "a-1", "user-1", 500L);
        when(repo.findById("pay-1")).thenReturn(Optional.of(p));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(gateway.charge("pay-1", 500L, "user-1")).thenReturn(PaymentGateway.DECISION_FAILURE);

        service.processPayment("pay-1");

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(p.getFailReason()).isEqualTo("gateway declined");

        ArgumentCaptor<PaymentFailedEvent> cap = ArgumentCaptor.forClass(PaymentFailedEvent.class);
        verify(publisher).publishPaymentFailed(cap.capture());
        assertThat(cap.getValue().paymentId()).isEqualTo("pay-1");
        assertThat(cap.getValue().reason()).isEqualTo("gateway declined");
    }

    @Test
    void processPayment_storedDecisionSuccess_reusesDecisionWithoutCallingGateway() {
        Payment p = pending("pay-1", "a-1", "user-1", 500L);
        p.setGatewayDecision(PaymentGateway.DECISION_SUCCESS); // already decided
        when(repo.findById("pay-1")).thenReturn(Optional.of(p));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.processPayment("pay-1");

        verifyNoInteractions(gateway); // gateway NOT called again
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        verify(publisher).publishPaymentProcessed(any());
    }

    @Test
    void processPayment_storedDecisionFailure_reusesDecisionWithoutCallingGateway() {
        Payment p = pending("pay-1", "a-1", "user-1", 500L);
        p.setGatewayDecision(PaymentGateway.DECISION_FAILURE);
        when(repo.findById("pay-1")).thenReturn(Optional.of(p));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.processPayment("pay-1");

        verifyNoInteractions(gateway);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.FAILED);
        verify(publisher).publishPaymentFailed(any());
    }

    // ── abandonPayment ─────────────────────────────────────────────────────────

    @Test
    void abandonPayment_setsFailedWithMaxRetriesReasonAndPublishes() {
        Payment p = pending("pay-1", "a-1", "user-1", 500L);
        when(repo.findById("pay-1")).thenReturn(Optional.of(p));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.abandonPayment("pay-1");

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(p.getFailReason()).isEqualTo("max retries exceeded");

        ArgumentCaptor<PaymentFailedEvent> cap = ArgumentCaptor.forClass(PaymentFailedEvent.class);
        verify(publisher).publishPaymentFailed(cap.capture());
        assertThat(cap.getValue().reason()).isEqualTo("max retries exceeded");
    }

    @Test
    void abandonPayment_notFound_throws404() {
        when(repo.findById("pay-x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.abandonPayment("pay-x"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    // ── incrementRetryCount ────────────────────────────────────────────────────

    @Test
    void incrementRetryCount_incrementsCountAndResetsToPending() {
        Payment p = pending("pay-1", "a-1", "user-1", 500L);
        p.setRetryCount(1);
        p.setStatus(PaymentStatus.PROCESSING);
        when(repo.findById("pay-1")).thenReturn(Optional.of(p));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.incrementRetryCount("pay-1");

        assertThat(p.getRetryCount()).isEqualTo(2);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PENDING);
        verify(repo).save(p);
    }

    // ── getByAuctionId ─────────────────────────────────────────────────────────

    @Test
    void getByAuctionId_empty_throws404() {
        when(repo.findByAuctionId("a-x")).thenReturn(List.of());

        assertThatThrownBy(() -> service.getByAuctionId("a-x"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void getByAuctionId_found_returnsList() {
        Payment p = pending("pay-1", "a-1", "user-1", 500L);
        when(repo.findByAuctionId("a-1")).thenReturn(List.of(p));

        List<Payment> result = service.getByAuctionId("a-1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo("pay-1");
    }
}
