package com.dropbid.payment.events;

import com.dropbid.payment.model.Payment;
import com.dropbid.payment.service.PaymentService;
import com.dropbid.shared.events.AuctionClosedEvent;
import com.dropbid.shared.streaming.ResilientStreamConsumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Consumes the {@code auction:closed} Redis Stream with consumer group
 * {@code payment-service}.
 *
 * Uses N worker virtual threads for parallel processing. PEL reclaim is
 * handled by the base class {@link ResilientStreamConsumer}.
 */
@Component
public class AuctionClosedConsumer extends ResilientStreamConsumer {

    private static final Logger log = LoggerFactory.getLogger(AuctionClosedConsumer.class);

    @Value("${payment.consumer.num-workers:10}")
    private int numWorkers;

    @Value("${payment.consumer.reclaim-interval-ms:30000}")
    private long reclaimInterval;

    @Value("${payment.consumer.pending-timeout-ms:30000}")
    private long pendingTimeout;

    private final PaymentService paymentService;
    private final ObjectMapper mapper;

    public AuctionClosedConsumer(StringRedisTemplate redis,
                                  PaymentService paymentService,
                                  ObjectMapper mapper) {
        super(redis);
        this.paymentService = paymentService;
        this.mapper = mapper;
    }

    @Override protected String stream() { return "auction:closed"; }
    @Override protected String group() { return "payment-service"; }
    @Override protected String consumerName() { return "payment-consumer-0"; }
    @Override protected int batchSize() { return 5; }
    @Override protected long reclaimIntervalMs() { return reclaimInterval; }
    @Override protected long pendingTimeoutMs() { return pendingTimeout; }

    @Override
    @PostConstruct
    protected void init() {
        ensureGroup();
        // N worker virtual threads for parallel payment processing
        for (int i = 0; i < numWorkers; i++) {
            final String name = "payment-consumer-" + i;
            Thread.ofVirtual().name("payment-closed-" + i).start(() -> workerLoop(name));
        }
        // Reclaim thread from base class
        Thread.ofVirtual().name(consumerName() + "-reclaim").start(this::startReclaimLoop);
    }

    private void workerLoop(String workerName) {
        log.info("Payment worker started: group={} consumer={}", group(), workerName);
        while (!Thread.currentThread().isInterrupted()) {
            try {
                List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                        Consumer.from(group(), workerName),
                        StreamReadOptions.empty().count(batchSize()).block(blockTimeout()),
                        StreamOffset.create(stream(), ReadOffset.lastConsumed())
                );
                if (records == null || records.isEmpty()) continue;

                for (MapRecord<String, Object, Object> record : records) {
                    try {
                        handleMessage(record);
                        redis.opsForStream().acknowledge(stream(), group(), record.getId());
                    } catch (Exception e) {
                        log.error("[{}] failed to process record {}: {}",
                                workerName, record.getId(), e.getMessage(), e);
                    }
                }
            } catch (Exception e) {
                log.error("[{}] error in worker loop: {}", workerName, e.getMessage(), e);
                try { Thread.sleep(1000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    @Override
    protected void handleMessage(MapRecord<String, Object, Object> record) {
        try {
            String json = (String) record.getValue().get("data");
            AuctionClosedEvent event = mapper.readValue(json, AuctionClosedEvent.class);
            List<Payment> payments = paymentService.initiatePayments(event);
            for (Payment payment : payments) {
                paymentService.processPayment(payment.getId());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void ensureGroup() {
        try {
            redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) conn -> {
                conn.streamCommands().xGroupCreate(
                        stream().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        group(),
                        ReadOffset.from("0"),
                        true
                );
                return null;
            });
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                log.debug("Consumer group {} already exists", group());
            } else {
                log.warn("Could not create consumer group {}: {}", group(), e.getMessage());
            }
        }
    }
}
