package com.dropbid.auction.concurrency;

import io.micrometer.core.instrument.MeterRegistry;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Holds the active {@link BidStrategy}.
 * Current strategy: {@link PessimisticStrategy} (Redisson distributed lock).
 *
 * Alternative implementations are in concurrency/experimental/ for reference.
 */
@Component
public class StrategyManager {

    private final BidStrategy active;

    public StrategyManager(RedissonClient redisson, StringRedisTemplate redis, MeterRegistry meters) {
        this.active = new PessimisticStrategy(redisson, redis, meters);
    }

    public BidStrategy current() { return active; }
}
