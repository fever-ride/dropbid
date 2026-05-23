package com.dropbid.query.events;

import com.dropbid.query.repository.BidActivityRepository;
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
 * Consumes both {@code payment:processed} and {@code payment:failed} streams.
 * Uses two inner consumer instances sharing the same service logic.
 */
@Component
public class PaymentEventConsumer {

    private final StringRedisTemplate redis;
    private final BidActivityRepository bidRepo;
    private final ObjectMapper mapper;

    public PaymentEventConsumer(StringRedisTemplate redis,
                                 BidActivityRepository bidRepo,
                                 ObjectMapper mapper) {
        this.redis = redis;
        this.bidRepo = bidRepo;
        this.mapper = mapper;
    }

    @PostConstruct
    void start() {
        new ProcessedConsumer(redis, bidRepo, mapper).init();
        new FailedConsumer(redis, bidRepo, mapper).init();
    }

    static class ProcessedConsumer extends ResilientStreamConsumer {
        private final BidActivityRepository bidRepo;
        private final ObjectMapper mapper;

        ProcessedConsumer(StringRedisTemplate redis, BidActivityRepository bidRepo, ObjectMapper mapper) {
            super(redis);
            this.bidRepo = bidRepo;
            this.mapper = mapper;
        }

        @Override protected String stream() { return "payment:processed"; }
        @Override protected String group() { return "query-service"; }
        @Override protected String consumerName() { return "query-pay-ok-1"; }

        @Override
        protected void handleMessage(MapRecord<String, Object, Object> record) {
            try {
                String json = (String) record.getValue().get("data");
                PaymentProcessedEvent event = mapper.readValue(json, PaymentProcessedEvent.class);
                bidRepo.findByAuctionIdAndBidderId(event.auctionId(), event.userId())
                        .ifPresent(ba -> {
                            ba.setPaymentStatus("COMPLETED");
                            ba.setPaymentId(event.paymentId());
                            ba.setUpdatedAt(Instant.now());
                            bidRepo.save(ba);
                        });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    static class FailedConsumer extends ResilientStreamConsumer {
        private final BidActivityRepository bidRepo;
        private final ObjectMapper mapper;

        FailedConsumer(StringRedisTemplate redis, BidActivityRepository bidRepo, ObjectMapper mapper) {
            super(redis);
            this.bidRepo = bidRepo;
            this.mapper = mapper;
        }

        @Override protected String stream() { return "payment:failed"; }
        @Override protected String group() { return "query-service"; }
        @Override protected String consumerName() { return "query-pay-fail-1"; }

        @Override
        protected void handleMessage(MapRecord<String, Object, Object> record) {
            try {
                String json = (String) record.getValue().get("data");
                PaymentFailedEvent event = mapper.readValue(json, PaymentFailedEvent.class);
                bidRepo.findByAuctionIdAndBidderId(event.auctionId(), event.userId())
                        .ifPresent(ba -> {
                            ba.setPaymentStatus("FAILED");
                            ba.setPaymentId(event.paymentId());
                            ba.setUpdatedAt(Instant.now());
                            bidRepo.save(ba);
                        });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
