package com.dropbid.query.events;

import com.dropbid.query.model.UserLookup;
import com.dropbid.query.repository.UserLookupRepository;
import com.dropbid.shared.events.UserUpdatedEvent;
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
public class UserUpdatedConsumer {

    private static final Logger log = LoggerFactory.getLogger(UserUpdatedConsumer.class);

    private static final String STREAM        = "user:updated";
    private static final String GROUP         = "query-service";
    private static final String CONSUMER_NAME = "query-user-consumer-1";
    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(2);

    private final StringRedisTemplate    redis;
    private final UserLookupRepository   repo;
    private final ObjectMapper           mapper;

    public UserUpdatedConsumer(StringRedisTemplate redis,
                                UserLookupRepository repo,
                                ObjectMapper mapper) {
        this.redis  = redis;
        this.repo   = repo;
        this.mapper = mapper;
    }

    @PostConstruct
    void start() {
        ensureConsumerGroup();
        Thread.ofVirtual().name("query-user-consumer").start(this::consumeLoop);
    }

    private void consumeLoop() {
        log.info("Query user consumer started on stream={} group={}", STREAM, GROUP);
        while (!Thread.currentThread().isInterrupted()) {
            try {
                List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                        Consumer.from(GROUP, CONSUMER_NAME),
                        StreamReadOptions.empty().count(20).block(BLOCK_TIMEOUT),
                        StreamOffset.create(STREAM, ReadOffset.lastConsumed())
                );
                if (records == null || records.isEmpty()) continue;

                for (MapRecord<String, Object, Object> record : records) {
                    processRecord(record);
                }
            } catch (Exception e) {
                log.error("Error in query user consumer: {}", e.getMessage(), e);
                try { Thread.sleep(1000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void processRecord(MapRecord<String, Object, Object> record) {
        try {
            String json = (String) record.getValue().get("data");
            UserUpdatedEvent event = mapper.readValue(json, UserUpdatedEvent.class);

            UserLookup lookup = repo.findById(event.userId()).orElseGet(() -> {
                UserLookup u = new UserLookup();
                u.setUserId(event.userId());
                return u;
            });
            lookup.setUsername(event.username());
            lookup.setRole(event.role());
            lookup.setUpdatedAt(Instant.now());
            repo.save(lookup);

            redis.opsForStream().acknowledge(STREAM, GROUP, record.getId());
        } catch (Exception e) {
            log.error("Failed to process user:updated record {}: {}", record.getId(), e.getMessage(), e);
        }
    }

    private void ensureConsumerGroup() {
        try {
            redis.opsForStream().createGroup(STREAM, ReadOffset.from("0"), GROUP);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                log.debug("Consumer group {} already exists for {}", GROUP, STREAM);
            } else {
                log.warn("Could not create consumer group {} for {}: {}", GROUP, STREAM, e.getMessage());
            }
        }
    }
}
