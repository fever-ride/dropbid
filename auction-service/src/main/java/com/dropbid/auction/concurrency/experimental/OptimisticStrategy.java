package com.dropbid.auction.concurrency.experimental;

import com.dropbid.auction.concurrency.BidResult;
import com.dropbid.auction.concurrency.BidStrategy;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

/**
 * Optimistic locking via Redis WATCH / MULTI / EXEC.
 *
 * NOT active — kept for reference. Current active strategy: PessimisticStrategy.
 *
 * Algorithm:
 *   1. WATCH auction:{id}
 *   2. Read current fields (hash)
 *   3. Validate: status=OPEN, amount > currentHighest
 *   4. MULTI → HMSET new highest/bidder/version+1/bidCount+1 → EXEC
 *   5. On EXEC=null (conflict), back off and retry up to 3 times
 *
 * Best for: low-contention auctions with high bid-acceptance rates.
 */
public class OptimisticStrategy implements BidStrategy {

    private static final Logger log = LoggerFactory.getLogger(OptimisticStrategy.class);
    private static final int MAX_RETRIES = 3;

    private final StringRedisTemplate redis;

    public OptimisticStrategy(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public String name() { return "optimistic"; }

    @Override
    @SuppressWarnings("unchecked")
    public BidResult tryPlaceBid(String auctionId, long amount, String bidderId)
            throws BidRejected, ConcurrencyEx {

        String key = "auction:" + auctionId;

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                try { Thread.sleep((long) Math.pow(2, attempt) * 10); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }

            BidResult result;
            try {
                result = redis.execute(new SessionCallback<>() {
                    @Override
                    public BidResult execute(RedisOperations ops) {
                        ops.watch(key);

                        Map<String, String> fields = ops.opsForHash().entries(key);
                        if (fields.isEmpty()) {
                            ops.unwatch();
                            return null;
                        }

                        String status = fields.get("status");
                        if (!"OPEN".equals(status)) {
                            ops.unwatch();
                            throw new IllegalStateException("CLOSED");
                        }

                        long currentHighest = parseLong(fields.get("current_highest"), 0);
                        if (amount <= currentHighest) {
                            ops.unwatch();
                            throw new IllegalStateException("BID_TOO_LOW:" + currentHighest);
                        }

                        String prevBidder = fields.getOrDefault("highest_bidder", "");
                        long   prevAmount = currentHighest;
                        long   version    = parseLong(fields.get("version"), 0);
                        long   bidCount   = parseLong(fields.get("bid_count"), 0);

                        ops.multi();
                        ops.opsForHash().put(key, "current_highest", String.valueOf(amount));
                        ops.opsForHash().put(key, "highest_bidder",  bidderId);
                        ops.opsForHash().put(key, "version",         String.valueOf(version + 1));
                        ops.opsForHash().put(key, "bid_count",       String.valueOf(bidCount + 1));
                        List<Object> results = ops.exec();

                        if (results == null) return null;

                        return new BidResult(auctionId, bidderId, amount, version + 1,
                                bidCount + 1, prevBidder, prevAmount,
                                amount, bidderId, Map.of(bidderId, amount));
                    }
                });
            } catch (IllegalStateException e) {
                String msg = e.getMessage();
                if ("CLOSED".equals(msg)) throw new BidRejected("auction is not open");
                if (msg != null && msg.startsWith("BID_TOO_LOW:")) {
                    long current = parseLong(msg.substring("BID_TOO_LOW:".length()), 0);
                    throw new BidRejected("bid " + amount + " must exceed current highest " + current);
                }
                throw new ConcurrencyEx("unexpected state in optimistic strategy", e);
            }

            if (result != null) {
                log.debug("[optimistic] bid accepted auction={} attempt={}", auctionId, attempt + 1);
                return result;
            }
            log.debug("[optimistic] conflict, retrying auction={} attempt={}", auctionId, attempt + 1);
        }
        throw new ConcurrencyEx("too many optimistic conflicts on auction " + auctionId);
    }

    private static long parseLong(String s, long def) {
        if (s == null || s.isBlank()) return def;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return def; }
    }
}
