package com.dropbid.query.events;

import com.dropbid.query.repository.BidActivityRepository;
import com.dropbid.shared.events.PaymentFailedEvent;
import com.dropbid.shared.events.PaymentProcessedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private static final String PROCESSED_STREAM = "payment:processed";
    private static final String FAILED_STREAM    = "payment:failed";
    private static final String GROUP            = "query-service";
    private static final Duration BLOCK_TIMEOUT  = Duration.ofSeconds(2);

    private final StringRedisTemplate    redis;
    private final BidActivityRepository  bidRepo;
    private final ObjectMapper           mapper;

    public PaymentEventConsumer(StringRedisTemplate redis,
                                 BidActivityRepository bidRepo,
                                 ObjectMapper mapper) {
        this.redis   = redis;
        this.bidRepo = bidRepo;
        this.mapper  = mapper;
    }

    @PostConstruct
    void start() {
        ensureConsumerGroup(PROCESSED_STREAM);
        ensureConsumerGroup(FAILED_STREAM);
        Thread.ofVirtual().name("query-payment-processed").start(
                () -> consumeLoop(PROCESSED_STREAM, "query-pay-ok-1"));
        Thread.ofVirtual().name("query-payment-failed").start(
                () -> consumeLoop(FAILED_STREAM, "query-pay-fail-1"));
    }

    private void consumeLoop(String stream, String consumerName) {
        log.info("Query payment consumer started on stream={} group={}", stream, GROUP);
        while (!Thread.currentThread().isInterrupted()) {
            try {
                List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                        Consumer.from(GROUP, consumerName),
                        StreamReadOptions.empty().count(10).block(BLOCK_TIMEOUT),
                        StreamOffset.create(stream, ReadOffset.lastConsumed())
                );
                if (records == null || records.isEmpty()) continue;

                for (MapRecord<String, Object, Object> record : records) {
                    processRecord(stream, record);
                }
            } catch (Exception e) {
                log.error("Error in query payment consumer [{}]: {}", stream, e.getMessage(), e);
                try { Thread.sleep(1000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void processRecord(String stream, MapRecord<String, Object, Object> record) {
        try {
            String json = (String) record.getValue().get("data");

            if (PROCESSED_STREAM.equals(stream)) {
                PaymentProcessedEvent event = mapper.readValue(json, PaymentProcessedEvent.class);
                bidRepo.findByAuctionIdAndBidderId(event.auctionId(), event.userId())
                        .ifPresent(ba -> {
                            ba.setPaymentStatus("COMPLETED");
                            ba.setPaymentId(event.paymentId());
                            ba.setUpdatedAt(Instant.now());
                            bidRepo.save(ba);
                        });
            } else {
                PaymentFailedEvent event = mapper.readValue(json, PaymentFailedEvent.class);
                bidRepo.findByAuctionIdAndBidderId(event.auctionId(), event.userId())
                        .ifPresent(ba -> {
                            ba.setPaymentStatus("FAILED");
                            ba.setPaymentId(event.paymentId());
                            ba.setUpdatedAt(Instant.now());
                            bidRepo.save(ba);
                        });
            }

            redis.opsForStream().acknowledge(stream, GROUP, record.getId());
        } catch (Exception e) {
            log.error("Failed to process {} record {}: {}", stream, record.getId(), e.getMessage(), e);
        }
    }

    private void ensureConsumerGroup(String stream) {
        try {
            redis.opsForStream().createGroup(stream, ReadOffset.from("0"), GROUP);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                log.debug("Consumer group {} already exists for {}", GROUP, stream);
            } else {
                log.warn("Could not create consumer group {} for {}: {}", GROUP, stream, e.getMessage());
            }
        }
    }
}
