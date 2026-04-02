package com.dropbid.auction.concurrency;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
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
 * Pessimistic locking using a Redisson {@link RLock} + a Lua script for atomic
 * Redis read-validate-write.
 *
 * The Lua script (scripts/place_bid.lua) handles:
 *   - status check
 *   - bid validation against current floor
 *   - multi-winner slot management via a Sorted Set (auction:{id}:winners)
 *   - atomic HMSET of version / bid_count / current_highest
 *
 * The Redisson lock serialises concurrent bids for the same auction and protects
 * the subsequent DynamoDB write in AuctionService.
 *
 * Metrics:
 *   auction.lock.wait.seconds   time from tryLock() call to acquisition (p50/p95/p99)
 *   auction.lock.hold.seconds   time spent holding the lock
 *   auction.lock.acquisitions   counter tagged result=success|timeout|interrupted
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

    private final RedissonClient      redisson;
    private final StringRedisTemplate redis;

    private final Timer   lockWaitTimer;
    private final Timer   lockHoldTimer;
    private final Counter successCounter;
    private final Counter timeoutCounter;
    private final Counter interruptCounter;

    public PessimisticStrategy(RedissonClient redisson,
                               StringRedisTemplate redis,
                               MeterRegistry meters) {
        this.redisson = redisson;
        this.redis    = redis;

        this.lockWaitTimer    = Timer.builder("auction.lock.wait")
                .description("Time spent waiting to acquire the distributed auction lock")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meters);

        this.lockHoldTimer    = Timer.builder("auction.lock.hold")
                .description("Time spent holding the lock (Lua script + DynamoDB prep)")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meters);

        this.successCounter   = Counter.builder("auction.lock.acquisitions")
                .tag("result", "success").register(meters);

        this.timeoutCounter   = Counter.builder("auction.lock.acquisitions")
                .tag("result", "timeout").register(meters);

        this.interruptCounter = Counter.builder("auction.lock.acquisitions")
                .tag("result", "interrupted").register(meters);
    }

    @Override
    public String name() { return "pessimistic"; }

    @Override
    @SuppressWarnings("unchecked")
    public BidResult tryPlaceBid(String auctionId, long amount, String bidderId)
            throws BidRejected, ConcurrencyEx {

        RLock lock = redisson.getLock("lock:auction:" + auctionId);

        long waitStart = System.nanoTime();
        boolean acquired;
        try {
            acquired = lock.tryLock(500, 500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            interruptCounter.increment();
            throw new ConcurrencyEx("interrupted while acquiring lock", e);
        } finally {
            lockWaitTimer.record(System.nanoTime() - waitStart, TimeUnit.NANOSECONDS);
        }

        if (!acquired) {
            timeoutCounter.increment();
            throw new ConcurrencyEx("could not acquire lock for auction " + auctionId);
        }
        successCounter.increment();

        long holdStart = System.nanoTime();
        try {
            String hashKey    = "auction:" + auctionId;
            String winnersKey = hashKey + ":winners";

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

            // Read full winners snapshot while still holding the lock — guaranteed
            // consistent with the Lua write above, no concurrent bid can interfere
            Map<String, Long> currentWinners = new LinkedHashMap<>();
            var tuples = redis.opsForZSet().rangeWithScores(winnersKey, 0, -1);
            if (tuples != null) {
                for (var t : tuples) {
                    if (t.getValue() != null && t.getScore() != null) {
                        currentWinners.put(t.getValue(), t.getScore().longValue());
                    }
                }
            }

            log.debug("[pessimistic] bid accepted auction={} floor={} winners={}",
                    auctionId, newFloor, currentWinners.size());
            return new BidResult(auctionId, bidderId, amount, newVersion,
                    bidCount, prevBidder, prevAmount, newFloor, topBidder, currentWinners);

        } finally {
            lockHoldTimer.record(System.nanoTime() - holdStart, TimeUnit.NANOSECONDS);
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    private static long parseLong(String s, long def) {
        if (s == null || s.isBlank()) return def;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return def; }
    }
}
