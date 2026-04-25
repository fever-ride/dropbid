package com.dropbid.payment.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Mock payment gateway.
 * 90 % success rate mirrors the original Go implementation.
 * The caller stores the decision so retries are idempotent.
 */
@Component
public class PaymentGateway {

    public static final String DECISION_SUCCESS = "success";
    public static final String DECISION_FAILURE = "failure";

    /**
     * Simulate a gateway call. Returns {@code "success"} or {@code "failure"}.
     * In production this would be an HTTP call to a real payment provider.
     */
    public String charge(String paymentId, long amount, String userId) {
        // 90 % success rate
        return ThreadLocalRandom.current().nextInt(10) < 9 ? DECISION_SUCCESS : DECISION_FAILURE;
    }
}
