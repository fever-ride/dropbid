package com.dropbid.shop.events;

import com.dropbid.shared.events.ItemUpdatedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ItemEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ItemEventPublisher.class);
    private static final String STREAM = "item:updated";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public ItemEventPublisher(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis  = redis;
        this.mapper = mapper;
    }

    public void publish(ItemUpdatedEvent event) {
        try {
            String json = mapper.writeValueAsString(event);
            redis.opsForStream().add(MapRecord.create(STREAM, Map.of("data", json)));
            log.debug("published item:updated itemId={}", event.itemId());
        } catch (Exception e) {
            log.error("failed to publish item:updated for {}: {}", event.itemId(), e.getMessage());
        }
    }
}
