package com.dropbid.payment.events;

import com.dropbid.shared.events.PaymentFailedEvent;
import com.dropbid.shared.events.PaymentProcessedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class PaymentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventPublisher.class);
    private static final String STREAM_PROCESSED = "payment:processed";
    private static final String STREAM_FAILED    = "payment:failed";
    private static final String STREAM_DLQ       = "payment:dlq";   // dead-letter queue

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public PaymentEventPublisher(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis  = redis;
        this.mapper = mapper;
    }

    public void publishPaymentProcessed(PaymentProcessedEvent event) {
        publish(STREAM_PROCESSED, event);
    }

    public void publishPaymentFailed(PaymentFailedEvent event) {
        publish(STREAM_FAILED, event);
        // Also write to DLQ for manual inspection / replay
        publish(STREAM_DLQ, event);
    }

    private void publish(String stream, Object event) {
        try {
            String json = mapper.writeValueAsString(event);
            redis.opsForStream().add(
                    StreamRecords.newRecord()
                            .ofMap(Map.of("data", json))
                            .withStreamKey(stream)
            );
            log.debug("published to stream={}", stream);
        } catch (JsonProcessingException e) {
            log.error("failed to serialize event for stream {}: {}", stream, e.getMessage());
        }
    }
}
