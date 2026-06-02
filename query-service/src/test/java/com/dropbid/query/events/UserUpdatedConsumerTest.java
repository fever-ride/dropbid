package com.dropbid.query.events;

import com.dropbid.query.model.UserLookup;
import com.dropbid.query.repository.UserLookupRepository;
import com.dropbid.shared.events.UserUpdatedEvent;
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
class UserUpdatedConsumerTest {

    @Mock UserLookupRepository repo;
    @Mock StringRedisTemplate   redis;

    ObjectMapper mapper = new ObjectMapper();
    UserUpdatedConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new UserUpdatedConsumer(redis, repo, mapper);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private MapRecord<String, Object, Object> record(String json) {
        Map<Object, Object> body = new HashMap<>();
        body.put("data", json);
        return MapRecord.create("user:updated", body);
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    /** Brand-new user: a new UserLookup row is created with all fields populated. */
    @Test
    void newUser_createsLookupRowWithAllFields() throws Exception {
        when(repo.findById("user-1")).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        UserUpdatedEvent event = new UserUpdatedEvent(
                "user-1", "alice", "BUYER", Instant.now().toString());
        consumer.handleMessage(record(mapper.writeValueAsString(event)));

        ArgumentCaptor<UserLookup> captor = ArgumentCaptor.forClass(UserLookup.class);
        verify(repo).save(captor.capture());

        UserLookup saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo("user-1");
        assertThat(saved.getUsername()).isEqualTo("alice");
        assertThat(saved.getRole()).isEqualTo("BUYER");
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    /** Existing user: username / role updated on the same row. */
    @Test
    void existingUser_updatesUsernameAndRole() throws Exception {
        UserLookup existing = new UserLookup();
        existing.setUserId("user-2");
        existing.setUsername("old-name");
        existing.setRole("BUYER");

        when(repo.findById("user-2")).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        UserUpdatedEvent event = new UserUpdatedEvent(
                "user-2", "new-name", "SELLER", Instant.now().toString());
        consumer.handleMessage(record(mapper.writeValueAsString(event)));

        ArgumentCaptor<UserLookup> captor = ArgumentCaptor.forClass(UserLookup.class);
        verify(repo).save(captor.capture());

        UserLookup saved = captor.getValue();
        assertThat(saved.getUsername()).isEqualTo("new-name");
        assertThat(saved.getRole()).isEqualTo("SELLER");
        assertThat(saved).isSameAs(existing);
    }

    /** Malformed JSON must propagate as RuntimeException for stream-consumer retry. */
    @Test
    void handleMessage_malformedJson_throwsRuntimeException() {
        Map<Object, Object> body = new HashMap<>();
        body.put("data", "{ bad json");
        MapRecord<String, Object, Object> badRecord = MapRecord.create("user:updated", body);

        assertThrows(RuntimeException.class, () -> consumer.handleMessage(badRecord));
        verify(repo, never()).save(any());
    }
}
