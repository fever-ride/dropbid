package com.dropbid.payment.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.dropbid.payment.model.Payment;

/**
 * Recovery job that finds payments stuck in PENDING or PROCESSING state
 * and either retries them or marks them FAILED if max retries exceeded.
 *
 * Runs every 2 minutes. Threshold: stuck > 5 minutes.
 * Mirrors the Go RecoveryJob pattern, improved with @Transactional per call.
 */
@Component
public class RecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(RecoveryJob.class);

    @Value("${payment.recovery.stuck-threshold-minutes:5}")
    private int stuckThresholdMinutes;

    @Value("${payment.recovery.max-retries:1}")
    private int maxRetries;

    private final PaymentService service;

    public RecoveryJob(PaymentService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${payment.recovery.interval-ms:120000}")
    public void recoverStuckPayments() {
        Instant threshold = Instant.now().minus(stuckThresholdMinutes, ChronoUnit.MINUTES);
        var stuck = service.getStuckPayments(threshold);

        if (stuck.isEmpty()) {
            log.debug("Recovery: no stuck payments found");
            return;
        }

        log.info("Recovery: found {} stuck payment(s)", stuck.size());
        for (Payment payment : stuck) {
            try {
                recover(payment);
            } catch (Exception e) {
                log.error("Recovery failed for payment {}: {}", payment.getId(), e.getMessage(), e);
            }
        }
    }

    private void recover(Payment payment) {
        if (payment.getRetryCount() >= maxRetries) {
            log.warn("Abandoning payment {} (retries={})", payment.getId(), payment.getRetryCount());
            service.abandonPayment(payment.getId());
        } else {
            log.info("Retrying payment {} (retries={})", payment.getId(), payment.getRetryCount());
            service.incrementRetryCount(payment.getId());
            service.processPayment(payment.getId());
        }
    }
}
