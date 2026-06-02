package com.dropbid.auction.concurrency;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for {@code scripts/place_bid.lua}.
 *
 * <p>Tests run against a local Redis instance on {@code localhost:6379}.
 * If Redis is not reachable, all tests are skipped automatically (JUnit 5
 * assumption) so a missing Redis does not break the build — it only
 * downgrades coverage.
 *
 * <p>For CI pipelines without a local Redis, add a {@code services: redis}
 * step (GitHub Actions) or start Redis with {@code docker run -d -p 6379:6379 redis:7-alpine}
 * before running {@code mvn test}.
 *
 * <p>Branches covered:
 * <ol>
 *   <li>AUCTION_CLOSED — status != "OPEN"</li>
 *   <li>BID_TOO_LOW — amount <= current_highest (equal and below)</li>
 *   <li>PRICE_TOO_HIGH — amount > maxPrice (when maxPrice > 0)</li>
 *   <li>maxPrice = 0 — no ceiling, any amount accepted</li>
 *   <li>First bid, below capacity — winner added, floor stays at starting bid</li>
 *   <li>At capacity — evicts lowest winner, returns prev bidder/amount</li>
 *   <li>Floor recalculates to lowest remaining winner after eviction</li>
 *   <li>Same bidder bids again — ZADD updates score, no duplicate entry</li>
 *   <li>version + bid_count increment atomically on every accepted bid</li>
 *   <li>Winners snapshot returned at indices 6+ (alternating member / score)</li>
 * </ol>
 */
class PlaceBidLuaTest {

    private static final String REDIS_HOST = "localhost";
    private static final int    REDIS_PORT = 6379;

    Jedis   jedis;
    String  script;

    // ── lifecycle ────────────────────────────────────────────────────────────

    @BeforeAll
    static void requireRedis() {
        try (Jedis probe = new Jedis(REDIS_HOST, REDIS_PORT)) {
            probe.ping();
        } catch (JedisConnectionException e) {
            Assumptions.assumeTrue(false,
                    "Redis not reachable at " + REDIS_HOST + ":" + REDIS_PORT +
                    " — Lua integration tests skipped. Start Redis or add " +
                    "'docker run -d -p 6379:6379 redis:7-alpine' to CI.");
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        jedis = new Jedis(REDIS_HOST, REDIS_PORT);
        // Use a test-specific DB (DB 15) to avoid colliding with any running data
        jedis.select(15);
        jedis.flushDB();
        try (var is = getClass().getResourceAsStream("/scripts/place_bid.lua")) {
            script = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @AfterEach
    void tearDown() {
        if (jedis != null) {
            jedis.flushDB(); // clean up DB 15 after each test
            jedis.close();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Set up the auction hash required by the Lua script.
     *
     * @param floor     initial value of {@code current_highest} (starting bid)
     * @param maxPrice  0 = no ceiling
     * @param qty       number of winner slots
     */
    void setupAuction(String id, String status, long floor, long maxPrice, long qty) {
        jedis.hset("auction:" + id, Map.of(
                "status",           status,
                "current_highest",  String.valueOf(floor),
                "max_price",        String.valueOf(maxPrice),
                "quantity",         String.valueOf(qty),
                "version",          "0",
                "bid_count",        "0"
        ));
    }

    @SuppressWarnings("unchecked")
    List<Object> bid(String auctionId, long amount, String bidderId) {
        return (List<Object>) jedis.eval(
                script,
                List.of("auction:" + auctionId, "auction:" + auctionId + ":winners"),
                List.of(String.valueOf(amount), bidderId));
    }

    static String str(Object o) { return String.valueOf(o); }
    static long   lng(Object o) { return Long.parseLong(String.valueOf(o)); }

    // ── 1. Auction not open ──────────────────────────────────────────────────

    @Test
    void auctionClosed_throwsAuctionClosed() {
        setupAuction("a1", "CLOSED", 500, 0, 3);

        assertThatThrownBy(() -> bid("a1", 600, "buyer-1"))
                .isInstanceOf(JedisDataException.class)
                .hasMessageContaining("AUCTION_CLOSED");
    }

    // ── 2. Bid too low ───────────────────────────────────────────────────────

    @Test
    void bidEqualToFloor_throwsBidTooLow() {
        setupAuction("a1", "OPEN", 500, 0, 3);

        // amount must be strictly greater than floor
        assertThatThrownBy(() -> bid("a1", 500, "buyer-1"))
                .isInstanceOf(JedisDataException.class)
                .hasMessageContaining("BID_TOO_LOW:500");
    }

    @Test
    void bidBelowFloor_throwsBidTooLowWithCurrentFloor() {
        setupAuction("a1", "OPEN", 500, 0, 3);

        assertThatThrownBy(() -> bid("a1", 400, "buyer-1"))
                .isInstanceOf(JedisDataException.class)
                .hasMessageContaining("BID_TOO_LOW:500");
    }

    // ── 3. Price too high ────────────────────────────────────────────────────

    @Test
    void bidExceedsMaxPrice_throwsPriceTooHighWithCeiling() {
        setupAuction("a1", "OPEN", 500, 1000, 3);

        assertThatThrownBy(() -> bid("a1", 1001, "buyer-1"))
                .isInstanceOf(JedisDataException.class)
                .hasMessageContaining("PRICE_TOO_HIGH:1000");
    }

    // ── 4. maxPrice = 0 means no ceiling ─────────────────────────────────────

    @Test
    void maxPriceZero_noCeiling_veryHighBidAccepted() {
        setupAuction("a1", "OPEN", 500, 0, 3);

        // Should not throw for an arbitrarily large bid
        assertThatNoException().isThrownBy(() -> bid("a1", 999_999_999, "buyer-1"));
    }

    // ── 5. First bid, below capacity ─────────────────────────────────────────

    @Test
    void firstBid_belowCapacity_addsWinnerAndPreservesFloor() {
        setupAuction("a1", "OPEN", 500, 0, 3); // qty=3 — plenty of room

        List<Object> result = bid("a1", 600, "buyer-1");

        assertThat(lng(result.get(0))).isEqualTo(1L);      // version incremented
        assertThat(lng(result.get(1))).isEqualTo(1L);      // bid_count incremented
        assertThat(str(result.get(2))).isEqualTo("");      // no eviction
        assertThat(lng(result.get(3))).isEqualTo(0L);      // no evicted amount
        assertThat(lng(result.get(4))).isEqualTo(500L);    // floor = starting bid (not at capacity)
        assertThat(str(result.get(5))).isEqualTo("buyer-1"); // top bidder

        // Hash must reflect the new state
        assertThat(jedis.hget("auction:a1", "current_highest")).isEqualTo("500");
        assertThat(jedis.hget("auction:a1", "version")).isEqualTo("1");
        // Winner is in the sorted set
        assertThat(jedis.zscore("auction:a1:winners", "buyer-1")).isEqualTo(600.0);
    }

    // ── 6. At capacity — evicts the lowest winner ─────────────────────────────

    @Test
    void atCapacity_newBidHigherThanFloor_evictsLowestWinner() {
        setupAuction("a1", "OPEN", 500, 0, 1); // qty=1

        // Fill the single slot
        bid("a1", 600, "old-buyer");

        // Higher bid must evict old-buyer
        List<Object> result = bid("a1", 800, "new-buyer");

        assertThat(str(result.get(2))).isEqualTo("old-buyer"); // evicted bidder
        assertThat(lng(result.get(3))).isEqualTo(600L);        // evicted amount
        assertThat(str(result.get(5))).isEqualTo("new-buyer"); // new top

        // Sorted set must only contain the new winner
        assertThat(jedis.zscore("auction:a1:winners", "old-buyer")).isNull();
        assertThat(jedis.zscore("auction:a1:winners", "new-buyer")).isEqualTo(800.0);
    }

    // ── 7. Floor recalculates to lowest remaining winner after eviction ───────

    @Test
    void eviction_newFloor_isLowestRemainingWinner() {
        setupAuction("a1", "OPEN", 500, 0, 2); // qty=2

        // Fill both slots: floor will settle at 600 after the second bid
        bid("a1", 600, "buyer-low");   // below capacity → floor stays 500
        bid("a1", 700, "buyer-high");  // reaches capacity → floor becomes 600
        assertThat(jedis.hget("auction:a1", "current_highest")).isEqualTo("600");

        // Now at capacity. This bid evicts buyer-low (score 600)
        List<Object> result = bid("a1", 750, "buyer-new");

        // After eviction: remaining winners are buyer-high @ 700 and buyer-new @ 750
        // New floor = 700 (the lowest remaining winner)
        assertThat(lng(result.get(4))).isEqualTo(700L);
        assertThat(jedis.hget("auction:a1", "current_highest")).isEqualTo("700");
    }

    // ── 8. Same bidder bids again — ZADD overwrites, no duplicate ────────────

    @Test
    void sameBidder_higherSecondBid_updatesScoreNoDuplicate() {
        setupAuction("a1", "OPEN", 500, 0, 3);

        bid("a1", 600, "buyer-1");
        bid("a1", 800, "buyer-1"); // same bidder, higher amount

        assertThat(jedis.zscore("auction:a1:winners", "buyer-1")).isEqualTo(800.0);
        assertThat(jedis.zcard("auction:a1:winners")).isEqualTo(1L); // still just one entry
    }

    // ── 9. version and bid_count increment on every accepted bid ─────────────

    @Test
    void versionAndBidCount_incrementAtomicallyPerBid() {
        setupAuction("a1", "OPEN", 100, 0, 5);

        bid("a1", 200, "buyer-1");
        bid("a1", 300, "buyer-2");
        bid("a1", 400, "buyer-3");

        assertThat(jedis.hget("auction:a1", "version")).isEqualTo("3");
        assertThat(jedis.hget("auction:a1", "bid_count")).isEqualTo("3");
    }

    // ── 10. Winners snapshot at indices 6+ ───────────────────────────────────

    @Test
    void winnersSnapshot_returnedAsAlternatingMemberScorePairsAtIndex6Plus() {
        setupAuction("a1", "OPEN", 500, 0, 3); // room for all

        bid("a1", 700, "buyer-A");
        bid("a1", 600, "buyer-B");
        List<Object> result = bid("a1", 800, "buyer-C");

        // 6 header fields + 3 winners × 2 (member + score) = 12 total
        assertThat(result.size()).isEqualTo(12);

        // Parse pairs from index 6 onward (ZRANGE returns ascending order by score)
        Map<String, Long> winners = new LinkedHashMap<>();
        for (int i = 6; i < result.size() - 1; i += 2) {
            winners.put(str(result.get(i)), lng(result.get(i + 1)));
        }

        assertThat(winners)
                .containsEntry("buyer-B", 600L)
                .containsEntry("buyer-A", 700L)
                .containsEntry("buyer-C", 800L);

        // topBidder (index 5) = buyer-C (highest score)
        assertThat(str(result.get(5))).isEqualTo("buyer-C");
    }
}
