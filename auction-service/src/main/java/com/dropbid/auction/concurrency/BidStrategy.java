package com.dropbid.auction.concurrency;

/**
 * Pluggable strategy for placing a bid under concurrency control.
 * Three implementations mirror the original Go strategies:
 *  - PessimisticStrategy — Atomic Lua script (Redis single-threaded execution)
 *  - OptimisticStrategy  — Redis WATCH/MULTI/EXEC (experimental)
 *  - QueueStrategy       — per-auction LinkedBlockingQueue (experimental, single-instance only)
 */
public interface BidStrategy {

    /**
     * Attempt to place a bid.  Must be idempotent and safe to call concurrently.
     *
     * @throws BidRejected    if the amount is too low or auction is not OPEN
     * @throws ConcurrencyEx  if the strategy could not acquire the necessary lock / resolve conflict
     */
    BidResult tryPlaceBid(String auctionId, long amount, String bidderId)
            throws BidRejected, ConcurrencyEx;

    /** Human-readable name used in admin responses and logs. */
    String name();

    // ── Checked exception types ────────────────────────────────────────────

    class BidRejected extends Exception {
        public BidRejected(String msg) { super(msg); }
    }

    class ConcurrencyEx extends Exception {
        public ConcurrencyEx(String msg) { super(msg); }
        public ConcurrencyEx(String msg, Throwable cause) { super(msg, cause); }
    }
}
