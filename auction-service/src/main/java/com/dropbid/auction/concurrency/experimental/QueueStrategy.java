package com.dropbid.auction.concurrency.experimental;

import com.dropbid.auction.concurrency.BidResult;
import com.dropbid.auction.concurrency.BidStrategy;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;
import java.util.concurrent.*;

/**
 * Queue-based (serial) strategy — one bid at a time per auction.
 *
 * NOT active — kept for reference. Current active strategy: PessimisticStrategy.
 * NOTE: in-memory queue breaks under horizontal scaling (multiple instances).
 *
 * Per-auction state:
 *  - {@link LinkedBlockingQueue} holds pending {@link BidTask}s (capacity 1000)
 *  - A single-threaded {@link ExecutorService} (virtual thread) drains the queue
 *
 * Best for: absolute serialization guarantee on a single instance.
 * Java 21 virtual threads make the per-auction executor very lightweight.
 */
public class QueueStrategy implements BidStrategy {

    private static final Logger log = LoggerFactory.getLogger(QueueStrategy.class);

    private final StringRedisTemplate redis;
    private final ConcurrentHashMap<String, AuctionQueue> queues = new ConcurrentHashMap<>();

    public QueueStrategy(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public String name() { return "queue"; }

    @Override
    public BidResult tryPlaceBid(String auctionId, long amount, String bidderId)
            throws BidRejected, ConcurrencyEx {

        AuctionQueue aq = queues.computeIfAbsent(auctionId, this::createAuctionQueue);

        BidTask task = new BidTask(auctionId, amount, bidderId);
        boolean offered;
        try {
            offered = aq.queue.offer(task, 2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConcurrencyEx("interrupted while queuing bid", e);
        }
        if (!offered) throw new ConcurrencyEx("auction queue full for " + auctionId);

        try {
            Object outcome = task.resultFuture.get(5, TimeUnit.SECONDS);
            if (outcome instanceof BidResult result) return result;
            if (outcome instanceof BidRejected  ex)   throw ex;
            throw new ConcurrencyEx("unexpected queue result type");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConcurrencyEx("interrupted waiting for queue result", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof BidRejected  br) throw br;
            if (cause instanceof ConcurrencyEx cx) throw cx;
            throw new ConcurrencyEx("queue executor error", cause);
        } catch (TimeoutException e) {
            throw new ConcurrencyEx("timed out waiting for queue result", e);
        }
    }

    private AuctionQueue createAuctionQueue(String auctionId) {
        LinkedBlockingQueue<BidTask> queue = new LinkedBlockingQueue<>(1000);
        ExecutorService executor = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("auction-queue-" + auctionId).factory()
        );
        executor.submit(() -> processQueue(auctionId, queue));
        return new AuctionQueue(queue, executor);
    }

    private void processQueue(String auctionId, BlockingQueue<BidTask> queue) {
        String key = "auction:" + auctionId;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                BidTask task = queue.poll(10, TimeUnit.SECONDS);
                if (task == null) continue;

                Map<Object, Object> fields = redis.opsForHash().entries(key);
                String status = (String) fields.get("status");

                if (!"OPEN".equals(status)) {
                    task.resultFuture.completeExceptionally(new BidRejected("auction is not open"));
                    continue;
                }

                long currentHighest = parseLong((String) fields.get("current_highest"), 0);
                if (task.amount <= currentHighest) {
                    task.resultFuture.completeExceptionally(
                            new BidRejected("bid " + task.amount + " must exceed " + currentHighest));
                    continue;
                }

                String prevBidder = (String) fields.getOrDefault("highest_bidder", "");
                long   version    = parseLong((String) fields.get("version"),   0);
                long   bidCount   = parseLong((String) fields.get("bid_count"), 0);

                redis.opsForHash().put(key, "current_highest", String.valueOf(task.amount));
                redis.opsForHash().put(key, "highest_bidder",  task.bidderId);
                redis.opsForHash().put(key, "version",         String.valueOf(version + 1));
                redis.opsForHash().put(key, "bid_count",       String.valueOf(bidCount + 1));

                log.debug("[queue] bid accepted auction={}", auctionId);
                task.resultFuture.complete(new BidResult(
                        auctionId, task.bidderId, task.amount, version + 1,
                        bidCount + 1, prevBidder, currentHighest,
                        task.amount, task.bidderId, Map.of(task.bidderId, task.amount)));

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static long parseLong(String s, long def) {
        if (s == null || s.isBlank()) return def;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return def; }
    }

    private record AuctionQueue(LinkedBlockingQueue<BidTask> queue, ExecutorService executor) {}

    private static class BidTask {
        final String auctionId;
        final long   amount;
        final String bidderId;
        final CompletableFuture<Object> resultFuture = new CompletableFuture<>();

        BidTask(String auctionId, long amount, String bidderId) {
            this.auctionId = auctionId;
            this.amount    = amount;
            this.bidderId  = bidderId;
        }
    }
}
