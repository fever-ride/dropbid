package com.dropbid.user.events;

import com.dropbid.shared.events.UserUpdatedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class UserEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(UserEventPublisher.class);
    private static final String STREAM = "user:updated";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public UserEventPublisher(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis  = redis;
        this.mapper = mapper;
    }

    public void publish(UserUpdatedEvent event) {
        try {
            String json = mapper.writeValueAsString(event);
            redis.opsForStream().add(MapRecord.create(STREAM, Map.of("data", json)));
            log.debug("published user:updated userId={}", event.userId());
        } catch (Exception e) {
            log.error("failed to publish user:updated for {}: {}", event.userId(), e.getMessage());
        }
    }
}
