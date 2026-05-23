package com.dropbid.auction.concurrency;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Holds the active {@link BidStrategy}.
 * Current strategy: {@link PessimisticStrategy} (atomic Lua script, no distributed lock).
 *
 * Alternative implementations are in concurrency/experimental/ for reference.
 */
@Component
public class StrategyManager {

    private final BidStrategy active;

    public StrategyManager(StringRedisTemplate redis, MeterRegistry meters) {
        this.active = new PessimisticStrategy(redis, meters);
    }

    public BidStrategy current() { return active; }
}
