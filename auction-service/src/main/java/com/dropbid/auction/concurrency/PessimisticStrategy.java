package com.dropbid.auction.concurrency;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Atomic bid placement using a single Redis Lua script.
 *
 * The Lua script (scripts/place_bid.lua) executes atomically on the Redis server,
 * guaranteeing serialization without an external distributed lock. Redis's
 * single-threaded execution model means concurrent bids on the same auction are
 * queued and processed one at a time by the server itself.
 *
 * The script handles:
 *   - status check
 *   - bid validation against current floor
 *   - multi-winner slot management via a Sorted Set (auction:{id}:winners)
 *   - atomic HMSET of version / bid_count / current_highest
 *   - atomic ZRANGE to snapshot the winners set (no gap for interference)
 */
public class PessimisticStrategy implements BidStrategy {

    private static final Logger log = LoggerFactory.getLogger(PessimisticStrategy.class);

    private static final DefaultRedisScript<List> PLACE_BID_SCRIPT;

    static {
        PLACE_BID_SCRIPT = new DefaultRedisScript<>();
        PLACE_BID_SCRIPT.setScriptSource(new ResourceScriptSource(
                new ClassPathResource("scripts/place_bid.lua")));
        PLACE_BID_SCRIPT.setResultType(List.class);
    }

    private final StringRedisTemplate redis;
    private final Timer bidTimer;

    public PessimisticStrategy(StringRedisTemplate redis, MeterRegistry meters) {
        this.redis = redis;

        this.bidTimer = Timer.builder("auction.bid.duration")
                .description("End-to-end time for a bid attempt (Lua script execution)")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meters);
    }

    @Override
    public String name() { return "pessimistic"; }

    @Override
    @SuppressWarnings("unchecked")
    public BidResult tryPlaceBid(String auctionId, long amount, String bidderId)
            throws BidRejected, ConcurrencyEx {

        String hashKey    = "auction:" + auctionId;
        String winnersKey = hashKey + ":winners";

        long start = System.nanoTime();
        List<Object> result;
        try {
            result = redis.execute(PLACE_BID_SCRIPT,
                    List.of(hashKey, winnersKey),
                    String.valueOf(amount), bidderId);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("AUCTION_CLOSED")) {
                throw new BidRejected("auction is not open");
            }
            if (msg.contains("BID_TOO_LOW:")) {
                long floor = parseLong(msg.replaceAll(".*BID_TOO_LOW:(\\d+).*", "$1"), 0);
                throw new BidRejected("bid " + amount + " must exceed current floor " + floor);
            }
            if (msg.contains("PRICE_TOO_HIGH:")) {
                long max = parseLong(msg.replaceAll(".*PRICE_TOO_HIGH:(\\d+).*", "$1"), 0);
                throw new BidRejected("bid " + amount + " exceeds maximum price " + max);
            }
            throw new ConcurrencyEx("Lua script execution failed", e);
        } finally {
            bidTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }

        if (result == null || result.size() < 6) {
            throw new ConcurrencyEx("unexpected Lua result for auction " + auctionId);
        }

        long   newVersion  = parseLong((String) result.get(0), 0);
        long   bidCount    = parseLong((String) result.get(1), 0);
        String prevBidder  = (String) result.get(2);
        long   prevAmount  = parseLong((String) result.get(3), 0);
        long   newFloor    = parseLong((String) result.get(4), 0);
        String topBidder   = (String) result.get(5);

        // Parse winners from Lua result (pairs of [member, score, member, score, ...] starting at index 6)
        Map<String, Long> currentWinners = new LinkedHashMap<>();
        for (int i = 6; i < result.size() - 1; i += 2) {
            String member = (String) result.get(i);
            long score = parseLong((String) result.get(i + 1), 0);
            currentWinners.put(member, score);
        }

        log.debug("[pessimistic] bid accepted auction={} floor={} winners={}",
                auctionId, newFloor, currentWinners.size());
        return new BidResult(auctionId, bidderId, amount, newVersion,
                bidCount, prevBidder, prevAmount, newFloor, topBidder, currentWinners);
    }

    private static long parseLong(String s, long def) {
        if (s == null || s.isBlank()) return def;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return def; }
    }
}
