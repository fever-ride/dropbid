package com.dropbid.query.events;

import com.dropbid.query.model.ItemLookup;
import com.dropbid.query.repository.ItemLookupRepository;
import com.dropbid.shared.events.ItemUpdatedEvent;
import com.dropbid.shared.streaming.ResilientStreamConsumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class ItemUpdatedConsumer extends ResilientStreamConsumer {

    private final ItemLookupRepository repo;
    private final ObjectMapper mapper;

    public ItemUpdatedConsumer(StringRedisTemplate redis,
                                ItemLookupRepository repo,
                                ObjectMapper mapper) {
        super(redis);
        this.repo = repo;
        this.mapper = mapper;
    }

    @Override protected String stream() { return "item:updated"; }
    @Override protected String group() { return "query-service"; }
    @Override protected String consumerName() { return "query-item-consumer-1"; }
    @Override protected int batchSize() { return 20; }

    @Override
    protected void handleMessage(MapRecord<String, Object, Object> record) {
        try {
            String json = (String) record.getValue().get("data");
            ItemUpdatedEvent event = mapper.readValue(json, ItemUpdatedEvent.class);

            ItemLookup lookup = repo.findById(event.itemId()).orElseGet(() -> {
                ItemLookup i = new ItemLookup();
                i.setItemId(event.itemId());
                return i;
            });
            lookup.setShopId(event.shopId());
            lookup.setTitle(event.title());
            lookup.setImageUrl(event.imageUrl());
            lookup.setSeries(event.series());
            lookup.setCondition(event.condition());
            lookup.setUpdatedAt(Instant.now());
            repo.save(lookup);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
