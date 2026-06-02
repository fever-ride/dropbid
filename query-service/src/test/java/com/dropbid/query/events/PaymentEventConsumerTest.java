package com.dropbid.query.events;

import com.dropbid.query.model.AuctionWinner;
import com.dropbid.query.repository.AuctionWinnerRepository;
import com.dropbid.shared.events.PaymentFailedEvent;
import com.dropbid.shared.events.PaymentProcessedEvent;
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
import static org.mockito.Mockito.*;

/**
 * Unit tests for the {@code ProcessedConsumer} and {@code FailedConsumer} inner
 * classes of {@link PaymentEventConsumer}.
 *
 * <p>Both inner classes are package-private, so tests in the same package can
 * instantiate them directly without Spring context or reflection.
 *
 * <p>{@code handleMessage} is called via a fake {@link MapRecord} so the full
 * JSON parsing path is exercised, not just the internal helper logic.
 */
@ExtendWith(MockitoExtension.class)
class PaymentEventConsumerTest {

    @Mock AuctionWinnerRepository winnerRepo;
    @Mock StringRedisTemplate      redis;

    ObjectMapper mapper = new ObjectMapper();

    PaymentEventConsumer.ProcessedConsumer processedConsumer;
    PaymentEventConsumer.FailedConsumer    failedConsumer;

    @BeforeEach
    void setUp() {
        // Instantiate inner consumers directly; @PostConstruct / init() is NOT
        // invoked — no Redis connection is needed.
        processedConsumer = new PaymentEventConsumer.ProcessedConsumer(redis, winnerRepo, mapper);
        failedConsumer    = new PaymentEventConsumer.FailedConsumer(redis, winnerRepo, mapper);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private MapRecord<String, Object, Object> record(String json) {
        Map<Object, Object> body = new HashMap<>();
        body.put("data", json);
        return MapRecord.create("stream", body);
    }

    private AuctionWinner winner(String auctionId, String bidderId) {
        AuctionWinner w = new AuctionWinner();
        w.setAuctionId(auctionId);
        w.setBidderId(bidderId);
        w.setAmount(500L);
        return w;
    }

    // ── ProcessedConsumer tests ───────────────────────────────────────────────

    /**
     * Happy path: payment:processed event updates the winner row with
     * paymentStatus=COMPLETED and sets the paymentId.
     */
    @Test
    void processedConsumer_updatesPaymentStatusToCompleted() throws Exception {
        AuctionWinner existing = winner("a1", "bidder-1");
        when(winnerRepo.findByAuctionIdAndBidderId("a1", "bidder-1"))
                .thenReturn(Optional.of(existing));
        when(winnerRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        PaymentProcessedEvent event = new PaymentProcessedEvent(
                "pay-abc", "a1", "bidder-1", 500L, Instant.now().toString());
        String json = mapper.writeValueAsString(event);

        processedConsumer.handleMessage(record(json));

        ArgumentCaptor<AuctionWinner> captor = ArgumentCaptor.forClass(AuctionWinner.class);
        verify(winnerRepo).save(captor.capture());

        AuctionWinner saved = captor.getValue();
        assertThat(saved.getPaymentStatus()).isEqualTo("COMPLETED");
        assertThat(saved.getPaymentId()).isEqualTo("pay-abc");
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    /**
     * No winner row found for the given (auctionId, userId) pair — nothing to
     * update; no exception, no save.
     */
    @Test
    void processedConsumer_winnerNotFound_doesNothing() throws Exception {
        when(winnerRepo.findByAuctionIdAndBidderId("a2", "bidder-2"))
                .thenReturn(Optional.empty());

        PaymentProcessedEvent event = new PaymentProcessedEvent(
                "pay-xyz", "a2", "bidder-2", 100L, Instant.now().toString());
        String json = mapper.writeValueAsString(event);

        processedConsumer.handleMessage(record(json));

        verify(winnerRepo, never()).save(any());
    }

    // ── FailedConsumer tests ──────────────────────────────────────────────────

    /**
     * Happy path: payment:failed event updates the winner row with
     * paymentStatus=FAILED and sets the paymentId.
     */
    @Test
    void failedConsumer_updatesPaymentStatusToFailed() throws Exception {
        AuctionWinner existing = winner("a3", "bidder-3");
        when(winnerRepo.findByAuctionIdAndBidderId("a3", "bidder-3"))
                .thenReturn(Optional.of(existing));
        when(winnerRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        PaymentFailedEvent event = new PaymentFailedEvent(
                "pay-fail-1", "a3", "bidder-3", "card_declined", 500L, Instant.now().toString());
        String json = mapper.writeValueAsString(event);

        failedConsumer.handleMessage(record(json));

        ArgumentCaptor<AuctionWinner> captor = ArgumentCaptor.forClass(AuctionWinner.class);
        verify(winnerRepo).save(captor.capture());

        AuctionWinner saved = captor.getValue();
        assertThat(saved.getPaymentStatus()).isEqualTo("FAILED");
        assertThat(saved.getPaymentId()).isEqualTo("pay-fail-1");
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    /**
     * No winner row found for payment:failed — silently ignored.
     */
    @Test
    void failedConsumer_winnerNotFound_doesNothing() throws Exception {
        when(winnerRepo.findByAuctionIdAndBidderId("a4", "bidder-4"))
                .thenReturn(Optional.empty());

        PaymentFailedEvent event = new PaymentFailedEvent(
                "pay-fail-2", "a4", "bidder-4", "insufficient_funds", 200L, Instant.now().toString());
        String json = mapper.writeValueAsString(event);

        failedConsumer.handleMessage(record(json));

        verify(winnerRepo, never()).save(any());
    }
}
