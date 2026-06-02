package com.dropbid.shared.streaming;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;

/**
 * Base class for Redis Stream consumers with built-in PEL reclaim.
 *
 * Subclasses implement {@link #handleMessage(MapRecord)} for business logic.
 * The base class handles: consume loop, ACK on success, PEL scan + redelivery
 * for messages stuck longer than {@link #pendingTimeoutMs()}, and optional
 * DLQ forwarding after max retries.
 */
public abstract class ResilientStreamConsumer {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private static final int DEFAULT_MAX_RETRIES = 5;

    protected final StringRedisTemplate redis;

    protected ResilientStreamConsumer(StringRedisTemplate redis) {
        this.redis = redis;
    }

    protected abstract String stream();
    protected abstract String group();
    protected abstract String consumerName();
    protected abstract void handleMessage(MapRecord<String, Object, Object> record);

    protected int batchSize() { return 10; }
    protected Duration blockTimeout() { return Duration.ofSeconds(2); }
    protected long reclaimIntervalMs() { return 30_000L; }
    protected long pendingTimeoutMs() { return 30_000L; }
    protected int maxRetries() { return DEFAULT_MAX_RETRIES; }
    protected boolean mkStream() { return true; }

    @PostConstruct
    public void init() {
        ensureConsumerGroup();
        Thread.ofVirtual().name(consumerName()).start(this::consumeLoop);
        Thread.ofVirtual().name(consumerName() + "-reclaim").start(this::reclaimLoop);
    }

    protected void startReclaimLoop() {
        reclaimLoop();
    }

    private void consumeLoop() {
        log.info("consumer started: stream={} group={} consumer={}", stream(), group(), consumerName());
        while (!Thread.currentThread().isInterrupted()) {
            try {
                List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                        Consumer.from(group(), consumerName()),
                        StreamReadOptions.empty().count(batchSize()).block(blockTimeout()),
                        StreamOffset.create(stream(), ReadOffset.lastConsumed())
                );
                if (records == null || records.isEmpty()) continue;
                processBatchWithAck(records);
            } catch (Exception e) {
                log.error("[{}] consume loop error: {}", consumerName(), e.getMessage(), e);
                try { Thread.sleep(1000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * Processes a batch of records by calling {@link #handleBatch}, then ACKs all
     * of them in one shot.  On failure, falls back to per-message processing so
     * that successfully processed messages still get ACKed and only the failing
     * one stays in the PEL for reclaim.
     */
    private void processBatchWithAck(List<MapRecord<String, Object, Object>> records) {
        try {
            handleBatch(records);
            String[] ids = records.stream()
                    .map(r -> r.getId().getValue())
                    .toArray(String[]::new);
            redis.opsForStream().acknowledge(stream(), group(), ids);
        } catch (Exception e) {
            log.error("[{}] batch handler failed ({}), falling back to per-message processing",
                    consumerName(), e.getMessage());
            for (MapRecord<String, Object, Object> record : records) {
                processWithAck(record);
            }
        }
    }

    /**
     * Override to process a full batch in one transaction / bulk DB call.
     * Default implementation delegates to {@link #handleMessage} one-by-one
     * (backward compatible with all existing consumers).
     */
    protected void handleBatch(List<MapRecord<String, Object, Object>> records) {
        for (MapRecord<String, Object, Object> record : records) {
            handleMessage(record);
        }
    }

    private void processWithAck(MapRecord<String, Object, Object> record) {
        try {
            handleMessage(record);
            redis.opsForStream().acknowledge(stream(), group(), record.getId());
        } catch (Exception e) {
            log.error("[{}] failed to process record {}: {}", consumerName(), record.getId(), e.getMessage(), e);
        }
    }

    // ── PEL reclaim loop ────────────────────────────────────────────────────

    private void reclaimLoop() {
        log.info("[{}] PEL reclaim loop started (interval={}ms, timeout={}ms)",
                consumerName(), reclaimIntervalMs(), pendingTimeoutMs());
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(reclaimIntervalMs());
                reclaimPendingMessages();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("[{}] reclaim error: {}", consumerName(), e.getMessage(), e);
            }
        }
    }

    private void reclaimPendingMessages() {
        var pending = redis.opsForStream().pending(stream(), group(),
                org.springframework.data.domain.Range.unbounded(), 50L);
        if (pending == null) return;

        for (PendingMessage pm : pending) {
            long elapsed = pm.getElapsedTimeSinceLastDelivery().toMillis();
            if (elapsed < pendingTimeoutMs()) continue;

            if (pm.getTotalDeliveryCount() > maxRetries()) {
                log.warn("[{}] message {} exceeded max retries ({}), sending to DLQ",
                        consumerName(), pm.getId(), maxRetries());
                sendToDlq(pm);
                redis.opsForStream().acknowledge(stream(), group(), pm.getId());
                continue;
            }

            log.info("[{}] reclaiming pending message {} (elapsed={}ms, deliveries={})",
                    consumerName(), pm.getId(), elapsed, pm.getTotalDeliveryCount());
            try {
                List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                        Consumer.from(group(), consumerName() + "-reclaim"),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(stream(), ReadOffset.from(pm.getId().getValue()))
                );
                if (records != null) {
                    for (MapRecord<String, Object, Object> r : records) {
                        processWithAck(r);
                    }
                }
            } catch (Exception e) {
                log.error("[{}] failed to reclaim message {}: {}",
                        consumerName(), pm.getId(), e.getMessage());
            }
        }
    }

    private void sendToDlq(PendingMessage pm) {
        String dlqStream = stream() + ":dlq";
        try {
            List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                    Consumer.from(group(), consumerName() + "-dlq"),
                    StreamReadOptions.empty().count(1),
                    StreamOffset.create(stream(), ReadOffset.from(pm.getId().getValue()))
            );
            if (records != null && !records.isEmpty()) {
                var original = records.get(0);
                redis.opsForStream().add(
                        org.springframework.data.redis.connection.stream.StreamRecords
                                .objectBacked(original.getValue())
                                .withStreamKey(dlqStream)
                );
            }
            log.warn("[{}] moved message {} to DLQ stream {}", consumerName(), pm.getId(), dlqStream);
        } catch (Exception e) {
            log.error("[{}] failed to move message {} to DLQ: {}", consumerName(), pm.getId(), e.getMessage());
        }
    }

    protected void ensureConsumerGroup() {
        try {
            if (mkStream()) {
                redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) conn -> {
                    conn.streamCommands().xGroupCreate(
                            stream().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            group(),
                            ReadOffset.from("0"),
                            true
                    );
                    return null;
                });
            } else {
                redis.opsForStream().createGroup(stream(), ReadOffset.from("0"), group());
            }
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                log.debug("consumer group {} already exists for stream {}", group(), stream());
            } else {
                log.warn("could not create consumer group {} for stream {}: {}",
                        group(), stream(), e.getMessage());
            }
        }
    }
}
