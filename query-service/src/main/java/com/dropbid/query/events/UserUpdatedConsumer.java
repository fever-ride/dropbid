package com.dropbid.query.events;

import com.dropbid.query.model.UserLookup;
import com.dropbid.query.repository.UserLookupRepository;
import com.dropbid.shared.events.UserUpdatedEvent;
import com.dropbid.shared.streaming.ResilientStreamConsumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class UserUpdatedConsumer extends ResilientStreamConsumer {

    private final UserLookupRepository repo;
    private final ObjectMapper mapper;

    public UserUpdatedConsumer(StringRedisTemplate redis,
                                UserLookupRepository repo,
                                ObjectMapper mapper) {
        super(redis);
        this.repo = repo;
        this.mapper = mapper;
    }

    @Override protected String stream() { return "user:updated"; }
    @Override protected String group() { return "query-service"; }
    @Override protected String consumerName() { return "query-user-consumer-1"; }
    @Override protected int batchSize() { return 20; }

    @Override
    protected void handleMessage(MapRecord<String, Object, Object> record) {
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
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
