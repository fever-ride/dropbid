package com.dropbid.auction.concurrency;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
class PessimisticStrategyTest {

    @Mock
    StringRedisTemplate redis;

    PessimisticStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new PessimisticStrategy(redis, new SimpleMeterRegistry());
    }

    // ── 1. name() ─────────────────────────────────────────────────────────────

    @Test
    void name_returnsPessimistic() {
        assertThat(strategy.name()).isEqualTo("pessimistic");
    }

    // ── 2. success — no winners beyond index 5 ────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void tryPlaceBid_success_returnsBidResult() throws Exception {
        List<Object> result = List.of("3", "7", "prevBidder1", "800", "900", "topBidder1");

        doReturn(result).when(redis).execute(
                any(RedisScript.class), anyList(), any(), any());

        BidResult br = strategy.tryPlaceBid("auction-1", 1000L, "buyer-A");

        assertThat(br.auctionId()).isEqualTo("auction-1");
        assertThat(br.bidderId()).isEqualTo("buyer-A");
        assertThat(br.amount()).isEqualTo(1000L);
        assertThat(br.newVersion()).isEqualTo(3L);
        assertThat(br.bidCount()).isEqualTo(7L);
        assertThat(br.previousBidder()).isEqualTo("prevBidder1");
        assertThat(br.previousHighest()).isEqualTo(800L);
        assertThat(br.newFloor()).isEqualTo(900L);
        assertThat(br.topBidder()).isEqualTo("topBidder1");
        assertThat(br.currentWinners()).isEmpty();
    }

    // ── 3. success — with winner pairs at index 6+ ────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void tryPlaceBid_withWinners_parsesWinnersMap() throws Exception {
        // Index 0-5: version, bidCount, prevBidder, prevAmount, newFloor, topBidder
        // Index 6+:  winner pairs (member, score alternating)
        List<Object> result = List.of(
                "2", "5", "loser1", "450", "500", "winner2",
                "winner1", "700",
                "winner2", "600"
        );

        doReturn(result).when(redis).execute(
                any(RedisScript.class), anyList(), any(), any());

        BidResult br = strategy.tryPlaceBid("auction-2", 700L, "winner1");

        assertThat(br.previousBidder()).isEqualTo("loser1");
        assertThat(br.previousHighest()).isEqualTo(450L);
        assertThat(br.currentWinners()).hasSize(2);
        assertThat(br.currentWinners()).containsEntry("winner1", 700L);
        assertThat(br.currentWinners()).containsEntry("winner2", 600L);
    }

    // ── 4. AUCTION_CLOSED → BidRejected ───────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void tryPlaceBid_auctionClosed_throwsBidRejected() {
        doThrow(new RuntimeException("ERR AUCTION_CLOSED"))
                .when(redis).execute(any(RedisScript.class), anyList(), any(), any());

        assertThatThrownBy(() -> strategy.tryPlaceBid("auction-3", 500L, "buyer-B"))
                .isInstanceOf(BidStrategy.BidRejected.class)
                .hasMessageContaining("auction is not open");
    }

    // ── 5. BID_TOO_LOW:{floor} → BidRejected with floor in message ───────────

    @Test
    @SuppressWarnings("unchecked")
    void tryPlaceBid_bidTooLow_throwsBidRejectedWithFloor() {
        doThrow(new RuntimeException("ERR BID_TOO_LOW:500"))
                .when(redis).execute(any(RedisScript.class), anyList(), any(), any());

        assertThatThrownBy(() -> strategy.tryPlaceBid("auction-4", 400L, "buyer-C"))
                .isInstanceOf(BidStrategy.BidRejected.class)
                .hasMessageContaining("500");
    }

    // ── 6. PRICE_TOO_HIGH:{max} → BidRejected with max in message ────────────

    @Test
    @SuppressWarnings("unchecked")
    void tryPlaceBid_priceTooHigh_throwsBidRejectedWithMax() {
        doThrow(new RuntimeException("ERR PRICE_TOO_HIGH:1000"))
                .when(redis).execute(any(RedisScript.class), anyList(), any(), any());

        assertThatThrownBy(() -> strategy.tryPlaceBid("auction-5", 1500L, "buyer-D"))
                .isInstanceOf(BidStrategy.BidRejected.class)
                .hasMessageContaining("1000");
    }

    // ── 7. unknown Redis error → ConcurrencyEx ────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void tryPlaceBid_unknownRedisError_throwsConcurrencyEx() {
        doThrow(new RuntimeException("connection refused"))
                .when(redis).execute(any(RedisScript.class), anyList(), any(), any());

        assertThatThrownBy(() -> strategy.tryPlaceBid("auction-6", 300L, "buyer-E"))
                .isInstanceOf(BidStrategy.ConcurrencyEx.class);
    }

    // ── 8. null result → ConcurrencyEx ───────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void tryPlaceBid_nullResult_throwsConcurrencyEx() {
        doReturn(null).when(redis).execute(
                any(RedisScript.class), anyList(), any(), any());

        assertThatThrownBy(() -> strategy.tryPlaceBid("auction-7", 200L, "buyer-F"))
                .isInstanceOf(BidStrategy.ConcurrencyEx.class);
    }

    // ── 9. short result (fewer than 6 elements) → ConcurrencyEx ──────────────

    @Test
    @SuppressWarnings("unchecked")
    void tryPlaceBid_shortResult_throwsConcurrencyEx() {
        doReturn(List.of("1", "2")).when(redis).execute(
                any(RedisScript.class), anyList(), any(), any());

        assertThatThrownBy(() -> strategy.tryPlaceBid("auction-8", 100L, "buyer-G"))
                .isInstanceOf(BidStrategy.ConcurrencyEx.class);
    }
}
