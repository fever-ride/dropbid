package com.dropbid.query.events;

import com.dropbid.query.repository.AuctionWinnerRepository;
import com.dropbid.shared.events.PaymentFailedEvent;
import com.dropbid.shared.events.PaymentProcessedEvent;
import com.dropbid.shared.streaming.ResilientStreamConsumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Consumes {@code payment:processed} and {@code payment:failed} events and
 * updates the {@code paymentStatus} / {@code paymentId} on the corresponding
 * {@code auction_winner} row.
 *
 * <p>Payment events only apply to winners, so the lookup is done on
 * {@code AuctionWinnerRepository} instead of the old {@code BidActivityRepository}.
 */
@Component
public class PaymentEventConsumer {

    private final StringRedisTemplate     redis;
    private final AuctionWinnerRepository winnerRepo;
    private final ObjectMapper            mapper;

    public PaymentEventConsumer(StringRedisTemplate redis,
                                 AuctionWinnerRepository winnerRepo,
                                 ObjectMapper mapper) {
        this.redis      = redis;
        this.winnerRepo = winnerRepo;
        this.mapper     = mapper;
    }

    @PostConstruct
    void start() {
        new ProcessedConsumer(redis, winnerRepo, mapper).init();
        new FailedConsumer(redis, winnerRepo, mapper).init();
    }

    // ── payment:processed ────────────────────────────────────────────────────

    static class ProcessedConsumer extends ResilientStreamConsumer {
        private final AuctionWinnerRepository winnerRepo;
        private final ObjectMapper            mapper;

        ProcessedConsumer(StringRedisTemplate redis,
                          AuctionWinnerRepository winnerRepo,
                          ObjectMapper mapper) {
            super(redis);
            this.winnerRepo = winnerRepo;
            this.mapper     = mapper;
        }

        @Override protected String stream()       { return "payment:processed"; }
        @Override protected String group()        { return "query-service"; }
        @Override protected String consumerName() { return "query-pay-ok-1"; }

        @Override
        protected void handleMessage(MapRecord<String, Object, Object> record) {
            try {
                String json = (String) record.getValue().get("data");
                PaymentProcessedEvent event = mapper.readValue(json, PaymentProcessedEvent.class);
                winnerRepo.findByAuctionIdAndBidderId(event.auctionId(), event.userId())
                        .ifPresent(w -> {
                            w.setPaymentStatus("COMPLETED");
                            w.setPaymentId(event.paymentId());
                            w.setUpdatedAt(Instant.now());
                            winnerRepo.save(w);
                        });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    // ── payment:failed ───────────────────────────────────────────────────────

    static class FailedConsumer extends ResilientStreamConsumer {
        private final AuctionWinnerRepository winnerRepo;
        private final ObjectMapper            mapper;

        FailedConsumer(StringRedisTemplate redis,
                       AuctionWinnerRepository winnerRepo,
                       ObjectMapper mapper) {
            super(redis);
            this.winnerRepo = winnerRepo;
            this.mapper     = mapper;
        }

        @Override protected String stream()       { return "payment:failed"; }
        @Override protected String group()        { return "query-service"; }
        @Override protected String consumerName() { return "query-pay-fail-1"; }

        @Override
        protected void handleMessage(MapRecord<String, Object, Object> record) {
            try {
                String json = (String) record.getValue().get("data");
                PaymentFailedEvent event = mapper.readValue(json, PaymentFailedEvent.class);
                winnerRepo.findByAuctionIdAndBidderId(event.auctionId(), event.userId())
                        .ifPresent(w -> {
                            w.setPaymentStatus("FAILED");
                            w.setPaymentId(event.paymentId());
                            w.setUpdatedAt(Instant.now());
                            winnerRepo.save(w);
                        });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
