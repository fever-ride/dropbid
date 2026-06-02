package com.dropbid.query.events;

import com.dropbid.query.model.ItemLookup;
import com.dropbid.query.repository.ItemLookupRepository;
import com.dropbid.shared.events.ItemUpdatedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ItemUpdatedConsumerTest {

    @Mock ItemLookupRepository repo;
    @Mock StringRedisTemplate   redis;

    ObjectMapper mapper = new ObjectMapper();
    ItemUpdatedConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ItemUpdatedConsumer(redis, repo, mapper);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static ItemUpdatedEvent event(String itemId) {
        return new ItemUpdatedEvent(itemId, "shop-1", "Title A", "img.jpg",
                "Series X", "NEW", Instant.now().toString());
    }

    private MapRecord<String, Object, Object> record(String json) {
        Map<Object, Object> body = new HashMap<>();
        body.put("data", json);
        return MapRecord.create("item:updated", body);
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    /** Brand-new item: new ItemLookup row created and all fields set. */
    @Test
    void newItem_createsLookupRowWithAllFields() throws Exception {
        when(repo.findById("item-1")).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        consumer.handleMessage(record(mapper.writeValueAsString(event("item-1"))));

        ArgumentCaptor<ItemLookup> captor = ArgumentCaptor.forClass(ItemLookup.class);
        verify(repo).save(captor.capture());

        ItemLookup saved = captor.getValue();
        assertThat(saved.getItemId()).isEqualTo("item-1");
        assertThat(saved.getShopId()).isEqualTo("shop-1");
        assertThat(saved.getTitle()).isEqualTo("Title A");
        assertThat(saved.getImageUrl()).isEqualTo("img.jpg");
        assertThat(saved.getSeries()).isEqualTo("Series X");
        assertThat(saved.getCondition()).isEqualTo("NEW");
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    /** Existing item: existing row is mutated and re-saved with latest fields. */
    @Test
    void existingItem_updatesFields() throws Exception {
        ItemLookup existing = new ItemLookup();
        existing.setItemId("item-2");
        existing.setTitle("Old Title");

        when(repo.findById("item-2")).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        ItemUpdatedEvent update = new ItemUpdatedEvent(
                "item-2", "shop-2", "New Title", "new-img.jpg",
                "Series Y", "USED", Instant.now().toString());
        consumer.handleMessage(record(mapper.writeValueAsString(update)));

        ArgumentCaptor<ItemLookup> captor = ArgumentCaptor.forClass(ItemLookup.class);
        verify(repo).save(captor.capture());

        ItemLookup saved = captor.getValue();
        assertThat(saved.getTitle()).isEqualTo("New Title");
        assertThat(saved.getShopId()).isEqualTo("shop-2");
        assertThat(saved.getCondition()).isEqualTo("USED");
        // Same object mutated in place
        assertThat(saved).isSameAs(existing);
    }

    /** Malformed JSON must propagate as RuntimeException so the stream consumer retries. */
    @Test
    void handleMessage_malformedJson_throwsRuntimeException() {
        Map<Object, Object> body = new HashMap<>();
        body.put("data", "{not valid json");
        MapRecord<String, Object, Object> record = MapRecord.create("item:updated", body);

        assertThrows(RuntimeException.class, () -> consumer.handleMessage(record));
        verify(repo, never()).save(any());
    }
}
