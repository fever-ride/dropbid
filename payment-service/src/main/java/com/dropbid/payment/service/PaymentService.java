package com.dropbid.payment.service;

import com.dropbid.payment.events.PaymentEventPublisher;
import com.dropbid.payment.model.Payment;
import com.dropbid.payment.model.PaymentStatus;
import com.dropbid.payment.repository.PaymentRepository;
import com.dropbid.shared.events.AuctionClosedEvent;
import com.dropbid.shared.events.PaymentFailedEvent;
import com.dropbid.shared.events.PaymentProcessedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private static final Duration LOCK_TTL = Duration.ofMinutes(5);

    private final PaymentRepository     repo;
    private final PaymentGateway        gateway;
    private final PaymentEventPublisher publisher;
    private final StringRedisTemplate   redis;

    public PaymentService(PaymentRepository repo,
                          PaymentGateway gateway,
                          PaymentEventPublisher publisher,
                          StringRedisTemplate redis) {
        this.repo      = repo;
        this.gateway   = gateway;
        this.publisher = publisher;
        this.redis     = redis;
    }

    // ── Initiation (called by auction:closed consumer) ────────────────────

    /**
     * Create one PENDING payment per auction winner.
     * Uses (auctionId, userId) as the idempotency guard so duplicate close events
     * do not create duplicate payments for the same winner.
     */
    @Transactional
    public List<Payment> initiatePayments(AuctionClosedEvent event) {
        if (event.winners() == null || event.winners().isEmpty()) {
            return List.of();
        }

        List<Payment> payments = new ArrayList<>();
        for (Map.Entry<String, Long> winner : event.winners().entrySet()) {
            String userId = winner.getKey();
            Long amount = winner.getValue();

            var existing = repo.findByAuctionIdAndUserId(event.auctionId(), userId);
            if (existing.isPresent()) {
                log.info("payment already exists for auction {} user {}, skipping", event.auctionId(), userId);
                payments.add(existing.get());
                continue;
            }

            Payment payment = new Payment();
            payment.setAuctionId(event.auctionId());
            payment.setUserId(userId);
            payment.setAmount(amount);
            payment.setStatus(PaymentStatus.PENDING);
            payments.add(repo.save(payment));
        }
        return payments;
    }

    // ── Processing ────────────────────────────────────────────────────────

    /**
     * Process a PENDING payment.  State machine:
     *   PENDING → PROCESSING (write gate)
     *   PROCESSING → COMPLETED | FAILED
     *
     * A Redis lock on paymentId prevents concurrent redeliveries from calling
     * the payment gateway twice for the same payment.
     */
    @Transactional
    public void processPayment(String paymentId) {
        String lockKey = "payment:lock:" + paymentId;
        Boolean acquired = redis.opsForValue().setIfAbsent(lockKey, "1", LOCK_TTL);
        if (!Boolean.TRUE.equals(acquired)) {
            log.info("payment {} is already being processed, skipping duplicate", paymentId);
            return;
        }

        try {
            doProcessPayment(paymentId);
        } finally {
            redis.delete(lockKey);
        }
    }

    private void doProcessPayment(String paymentId) {
        Payment payment = repo.findById(paymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "payment not found"));

        if (payment.getStatus() != PaymentStatus.PENDING
                && payment.getStatus() != PaymentStatus.PROCESSING) {
            log.info("payment {} is already in terminal state {}", paymentId, payment.getStatus());
            return;
        }

        // Write gate: mark PROCESSING before calling gateway
        payment.setStatus(PaymentStatus.PROCESSING);
        repo.save(payment);

        // Determine gateway decision (use stored decision for retries)
        String decision = payment.getGatewayDecision();
        if (decision == null) {
            decision = gateway.charge(paymentId, payment.getAmount(), payment.getUserId());
            payment.setGatewayDecision(decision);
        }

        if (PaymentGateway.DECISION_SUCCESS.equals(decision)) {
            payment.setStatus(PaymentStatus.COMPLETED);
            repo.save(payment);
            log.info("payment {} COMPLETED auction={}", paymentId, payment.getAuctionId());
            publisher.publishPaymentProcessed(new PaymentProcessedEvent(
                    paymentId, payment.getAuctionId(), payment.getUserId(),
                    payment.getAmount(), Instant.now().toString()
            ));
        } else {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailReason("gateway declined");
            repo.save(payment);
            log.warn("payment {} FAILED auction={}", paymentId, payment.getAuctionId());
            publisher.publishPaymentFailed(new PaymentFailedEvent(
                    paymentId, payment.getAuctionId(), payment.getUserId(),
                    "gateway declined", payment.getAmount(), Instant.now().toString()
            ));
        }
    }

    // ── Recovery helpers (called by RecoveryJob) ──────────────────────────

    @Transactional
    public void abandonPayment(String paymentId) {
        Payment payment = repo.findById(paymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "payment not found"));
        payment.setStatus(PaymentStatus.FAILED);
        payment.setFailReason("max retries exceeded");
        repo.save(payment);
        publisher.publishPaymentFailed(new PaymentFailedEvent(
                paymentId, payment.getAuctionId(), payment.getUserId(),
                "max retries exceeded", payment.getAmount(), Instant.now().toString()
        ));
    }

    @Transactional
    public void incrementRetryCount(String paymentId) {
        Payment payment = repo.findById(paymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "payment not found"));
        payment.setRetryCount(payment.getRetryCount() + 1);
        payment.setStatus(PaymentStatus.PENDING); // reset to PENDING for reprocessing
        repo.save(payment);
    }

    // ── Queries ──────────────────────────────────────────────────────────

    public Payment getPayment(String paymentId) {
        return repo.findById(paymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "payment not found"));
    }

    public List<Payment> getByAuctionId(String auctionId) {
        List<Payment> payments = repo.findByAuctionId(auctionId);
        if (payments.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "payment not found");
        }
        return payments;
    }

    public List<Payment> getUserPayments(String userId) {
        return repo.findByUserId(userId);
    }

    public List<Payment> getStuckPayments(java.time.Instant threshold) {
        return repo.findStuckPayments(
                List.of(PaymentStatus.PENDING, PaymentStatus.PROCESSING), threshold);
    }
}
