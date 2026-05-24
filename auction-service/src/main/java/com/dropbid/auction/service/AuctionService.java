package com.dropbid.auction.service;

import com.dropbid.auction.bid.model.Bid;
import com.dropbid.auction.bid.repository.BidStore;
import com.dropbid.auction.concurrency.BidResult;
import com.dropbid.auction.concurrency.BidStrategy;
import com.dropbid.auction.concurrency.StrategyManager;
import com.dropbid.auction.events.AuctionEventPublisher;
import com.dropbid.auction.model.Auction;
import com.dropbid.auction.repository.AuctionStore;
import com.dropbid.shared.events.AuctionClosedEvent;
import com.dropbid.shared.events.AuctionCreatedEvent;
import com.dropbid.shared.events.BidPlacedEvent;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import com.dropbid.shared.IdGenerator;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

@Service
public class AuctionService {

    private static final Logger log = LoggerFactory.getLogger(AuctionService.class);

    public static final String SCHEDULE_OPEN  = "auction:schedule:open";
    public static final String SCHEDULE_CLOSE = "auction:schedule:close";
    private static final String NULL_MARKER   = "__NULL__";
    private static final Duration NULL_TTL    = Duration.ofSeconds(60);

    private final AuctionStore           repo;
    private final BidStore               bidStore;
    private final StringRedisTemplate   redis;
    private final RedissonClient        redisson;
    private final AuctionEventPublisher publisher;
    private final StrategyManager       strategyManager;

    public AuctionService(AuctionStore repo,
                          BidStore bidStore,
                          StringRedisTemplate redis,
                          RedissonClient redisson,
                          AuctionEventPublisher publisher,
                          StrategyManager strategyManager) {
        this.repo            = repo;
        this.bidStore        = bidStore;
        this.redis           = redis;
        this.redisson        = redisson;
        this.publisher       = publisher;
        this.strategyManager = strategyManager;
    }

    // ── CRUD ────────────────────────────────────────────────────────────────

    public Auction createAuction(String sellerId, String shopId, String itemId,
                                 long startingBid, Long maxPrice,
                                 String startTime, String endTime, long quantity) {
        if (maxPrice != null && maxPrice <= startingBid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "maxPrice must exceed startingBid");
        }

        Auction auction = new Auction();
        auction.setAuctionId(IdGenerator.newId());
        auction.setItemId(itemId);
        auction.setShopId(shopId);
        auction.setSellerId(sellerId);
        auction.setStartingBid(startingBid);
        auction.setMaxPrice(maxPrice);
        auction.setCurrentHighest(startingBid);
        auction.setEndTime(endTime);
        auction.setQuantity(quantity);
        auction.setBidCount(0L);
        auction.setVersion(0L);

        Instant end = Instant.parse(endTime);
        if (!end.isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endTime must be in the future");
        }

        boolean scheduled = startTime != null && Instant.parse(startTime).isAfter(Instant.now());
        if (scheduled && !end.isAfter(Instant.parse(startTime))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endTime must be after startTime");
        }

        if (scheduled) {
            auction.setStatus("PENDING");
            auction.setStartTime(startTime);
        } else {
            auction.setStatus("OPEN");
            auction.setStartTime(Instant.now().toString());
        }

        // Persist to DynamoDB first — seedRedisCache must come after so that a
        // DynamoDB failure does not leave a ghost auction in Redis with no recovery record.
        repo.save(auction);

        if (!scheduled) {
            seedRedisCache(auction);
        }

        String auctionId = auction.getAuctionId();
        if (scheduled) {
            redis.opsForZSet().add(SCHEDULE_OPEN, auctionId,
                    Instant.parse(startTime).toEpochMilli());
        }
        redis.opsForZSet().add(SCHEDULE_CLOSE, auctionId,
                end.toEpochMilli());

        publisher.publishAuctionCreated(new AuctionCreatedEvent(
                auction.getAuctionId(),
                auction.getItemId(),
                auction.getShopId(),
                auction.getSellerId(),
                auction.getStartingBid(),
                auction.getStatus(),
                auction.getStartTime(),
                auction.getEndTime(),
                auction.getQuantity()
        ));

        log.info("created auction {} item={} status={}", auctionId, itemId, auction.getStatus());
        return auction;
    }

    public Auction getAuction(String auctionId) {
        return repo.findById(auctionId);
    }

    public List<Auction> listAuctions(String status) {
        return repo.findByStatus(status != null ? status : "OPEN");
    }

    // ── Bidding ─────────────────────────────────────────────────────────────

    /**
     * Route a bid through the active concurrency strategy.
     * On success: persist final state to DynamoDB + publish BidPlacedEvent.
     */
    public BidResult placeBid(String auctionId, long amount, String bidderId) {
        ensureRedisCached(auctionId);

        String sellerId = (String) redis.opsForHash().get("auction:" + auctionId, "seller_id");
        if (bidderId.equals(sellerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "seller cannot bid on their own auction");
        }

        BidStrategy strategy = strategyManager.current();
        BidResult result;
        try {
            result = strategy.tryPlaceBid(auctionId, amount, bidderId);
        } catch (BidStrategy.BidRejected e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (BidStrategy.ConcurrencyEx e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }

        // Persist final state back to DynamoDB asynchronously (best-effort)
        // Redis is the source of truth; closeAuction() writes the authoritative final state.
        CompletableFuture.runAsync(() -> {
            try {
                Auction auctionMeta = repo.findById(auctionId);
                auctionMeta.setCurrentHighest(result.newFloor());
                auctionMeta.setHighestBidder(result.topBidder());
                auctionMeta.setBidCount(result.bidCount());
                auctionMeta.setVersion(result.newVersion());
                auctionMeta.setWinners(result.currentWinners().isEmpty() ? null : result.currentWinners());
                repo.update(auctionMeta);
            } catch (ConditionalCheckFailedException e) {
                log.debug("DynamoDB conditional update skipped for auction {} (stale version)", auctionId);
            } catch (Exception e) {
                log.warn("async DynamoDB persist failed for auction {}: {}", auctionId, e.getMessage());
            }
        });

        // Publish event for Notification service (read itemId/sellerId from Redis)
        String itemId = (String) redis.opsForHash().get("auction:" + auctionId, "item_id");
        String bidId = IdGenerator.newId();
        String bidTime = Instant.now().toString();
        publisher.publishBidPlaced(new BidPlacedEvent(
                auctionId, bidId, itemId, sellerId,
                bidderId, amount, result.previousHighest(), result.previousBidder(),
                bidTime, bidTime
        ));

        // Async bid history recording — append-only insert, no updates ever.
        // Status (WINNING / OUTBID) is derived at read time from the live winners set,
        // so no status field needs to be written or updated here.
        CompletableFuture.runAsync(() -> {
            try {
                recordBidHistory(auctionId, bidId, bidderId, amount, bidTime);
            } catch (Exception e) {
                log.warn("bid history write failed for auction {}: {}", auctionId, e.getMessage());
            }
        });

        return result;
    }

    // ── Opening ─────────────────────────────────────────────────────────────

    /**
     * Called by {@link com.dropbid.auction.scheduler.AuctionOpener} when a
     * PENDING auction's startTime has arrived.
     */
    public void openAuction(String auctionId) {
        Auction auction = repo.findByIdOrNull(auctionId);
        if (auction == null || !"PENDING".equals(auction.getStatus())) return;

        auction.setStatus("OPEN");
        repo.updateUnconditional(auction);
        seedRedisCache(auction);
        redis.opsForZSet().remove(SCHEDULE_OPEN, auctionId);

        // Notify Query Service so auction_summary.status transitions PENDING → OPEN.
        // Reuses AuctionCreatedEvent — the consumer does an upsert and only updates
        // structural fields, so bidCount and lastBidId are not affected.
        publisher.publishAuctionCreated(new AuctionCreatedEvent(
                auction.getAuctionId(),
                auction.getItemId(),
                auction.getShopId(),
                auction.getSellerId(),
                auction.getStartingBid(),
                "OPEN",
                auction.getStartTime(),
                auction.getEndTime(),
                auction.getQuantity()
        ));

        log.info("opened auction {} item={}", auctionId, auction.getItemId());
    }

    /** Returns true if a PENDING auction's startTime is now in the past. */
    public boolean isReadyToOpen(Auction auction) {
        try {
            return Instant.parse(auction.getStartTime()).isBefore(Instant.now());
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    // ── Closing ─────────────────────────────────────────────────────────────

    /**
     * Called by the {@link com.dropbid.auction.scheduler.AuctionCloser} when
     * an auction's endTime has passed.
     */
    public void closeAuction(String auctionId) {
        Auction auction = repo.findByIdOrNull(auctionId);
        if (auction == null || !"OPEN".equals(auction.getStatus())) return;

        // ── 1. Build winners map ────────────────────────────────────────────
        String key        = "auction:" + auctionId;
        String winnersKey = key + ":winners";
        var winnerTuples  = redis.opsForZSet().rangeWithScores(winnersKey, 0, -1);

        Map<String, Long> winners = new java.util.LinkedHashMap<>();
        if (winnerTuples != null && !winnerTuples.isEmpty()) {
            for (var t : winnerTuples) {
                if (t.getValue() != null && t.getScore() != null) {
                    winners.put(t.getValue(), t.getScore().longValue());
                }
            }
        } else if (auction.getWinners() != null && !auction.getWinners().isEmpty()) {
            log.warn("auction {} ZSET missing, falling back to DynamoDB winners", auctionId);
            winners.putAll(auction.getWinners());
        } else if (auction.getHighestBidder() != null && !auction.getHighestBidder().isBlank()) {
            log.warn("auction {} winners missing, last resort DynamoDB highestBidder", auctionId);
            winners.put(auction.getHighestBidder(), auction.getCurrentHighest());
        }

        // ── 2. Publish event BEFORE marking closed ──────────────────────────
        // Always publish — even for no-bid auctions — so Query Service can
        // transition auction_summary.status to CLOSED.
        // If publish fails, status stays OPEN and the scheduler retries next tick.
        // If publish succeeds but the update below fails, downstream gets a
        // duplicate event on retry — consumers must be idempotent (they are,
        // via Redis Streams consumer-group ack).
        publisher.publishAuctionClosed(new AuctionClosedEvent(
                auctionId,
                winners.isEmpty() ? null : winners,
                auction.getItemId(),
                auction.getShopId(),
                Instant.now().toString()
        ));
        log.info("closed auction {} winners={}", auctionId, winners.isEmpty() ? "none" : winners);

        // ── 3. Mark CLOSED in DynamoDB and clean up Redis ───────────────────
        // Persist the authoritative winners so that BidController.resolveWinners()
        // and GET /auctions/{id} return correct data after Redis keys are deleted.
        auction.setStatus("CLOSED");
        auction.setWinners(winners.isEmpty() ? null : new java.util.LinkedHashMap<>(winners));
        repo.updateUnconditional(auction);
        redis.delete(key);
        redis.delete(winnersKey);
        redis.opsForZSet().remove(SCHEDULE_CLOSE, auctionId);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Rebuilds the Redis hot-path cache from DynamoDB if the key was evicted
     * or Redis was restarted. Called at the start of every placeBid().
     *
     * Uses a short-lived Redisson lock (lock:rebuild:{id}) so that under a
     * cache-miss burst only one thread hits DynamoDB — the rest wait and then
     * find the key already rebuilt.
     *
     * Cache penetration protection: when the auction doesn't exist or isn't
     * OPEN, a short-lived null marker is cached so repeated requests for the
     * same invalid ID don't keep hitting DynamoDB.
     */
    private void ensureRedisCached(String auctionId) {
        String cacheKey = "auction:" + auctionId;
        String nullKey  = "auction:null:" + auctionId;

        if (Boolean.TRUE.equals(redis.hasKey(cacheKey))) return;

        if (Boolean.TRUE.equals(redis.hasKey(nullKey))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "auction not found: " + auctionId);
        }

        RLock rebuildLock = redisson.getLock("lock:rebuild:" + auctionId);
        try {
            rebuildLock.lock();
            if (Boolean.TRUE.equals(redis.hasKey(cacheKey))) return;
            if (Boolean.TRUE.equals(redis.hasKey(nullKey))) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "auction not found: " + auctionId);
            }

            Auction auction = repo.findByIdOrNull(auctionId);
            if (auction == null) {
                redis.opsForValue().set(nullKey, NULL_MARKER, NULL_TTL);
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "auction not found: " + auctionId);
            }
            if (!"OPEN".equals(auction.getStatus())) {
                redis.opsForValue().set(nullKey, NULL_MARKER, NULL_TTL);
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "auction is not open");
            }
            seedRedisCache(auction);
            log.warn("rebuilt Redis cache for auction {} after cache miss", auctionId);
        } finally {
            if (rebuildLock.isHeldByCurrentThread()) rebuildLock.unlock();
        }
    }

    private void seedRedisCache(Auction a) {
        String key        = "auction:" + a.getAuctionId();
        String winnersKey = key + ":winners";

        // Restore the winners ZSET from the DynamoDB snapshot so that the Lua
        // script's slot-count and floor-price logic stays correct after a Redis
        // restart or cache rebuild.  Without this, a multi-winner auction would
        // believe all seats are empty and accept bids below the real floor.
        redis.delete(winnersKey);
        if (a.getWinners() != null && !a.getWinners().isEmpty()) {
            a.getWinners().forEach((bidderId, amount) ->
                    redis.opsForZSet().add(winnersKey, bidderId, (double) amount));
        }

        redis.opsForHash().putAll(key, Map.of(
                "auction_id",      a.getAuctionId(),
                "item_id",         a.getItemId(),
                "seller_id",       a.getSellerId(),
                "status",          a.getStatus(),
                "current_highest", String.valueOf(a.getCurrentHighest()),
                "highest_bidder",  a.getHighestBidder() != null ? a.getHighestBidder() : "",
                "bid_count",       String.valueOf(a.getBidCount()),
                "version",         String.valueOf(a.getVersion()),
                "quantity",        String.valueOf(a.getQuantity() != null ? a.getQuantity() : 1L)
        ));
        // max_price stored separately — Map.of() has a 10-entry limit
        redis.opsForHash().put(key, "max_price",
                String.valueOf(a.getMaxPrice() != null ? a.getMaxPrice() : 0L));
    }

    // ── Bid history helpers ──────────────────────────────────────────────────

    /**
     * Appends an immutable bid record to the Bids table.
     * No status field — WINNING / OUTBID is derived at read time by cross-referencing
     * the auction's live winners set (Redis) or final winners snapshot (DynamoDB).
     */
    private void recordBidHistory(String auctionId, String bidId, String bidderId,
                                  long amount, String bidTime) {
        Bid bid = new Bid();
        bid.setBidId(bidId);
        bid.setAuctionId(auctionId);
        bid.setBidderId(bidderId);
        bid.setAmount(amount);
        bid.setCreatedAt(bidTime);
        bidStore.save(bid);
        log.debug("recorded bid {} auction={} bidder={} amount={}", bidId, auctionId, bidderId, amount);
    }

    /** Returns true if the auction's endTime is in the past. */
    public boolean isExpired(Auction auction) {
        try {
            return Instant.parse(auction.getEndTime()).isBefore(Instant.now());
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /**
     * Returns auction IDs whose scheduled time has passed.
     * Used by AuctionOpener / AuctionCloser instead of full-table scans.
     */
    public Set<String> pollDueAuctionIds(String scheduleKey) {
        double now = Instant.now().toEpochMilli();
        Set<String> ids = redis.opsForZSet().rangeByScore(scheduleKey, 0, now);
        return ids != null ? ids : Set.of();
    }

    /**
     * Rebuild schedule sorted sets from DynamoDB on startup.
     * Covers the case where Redis was restarted and schedule data was lost.
     */
    public void rebuildSchedules() {
        int openCount = 0, closeCount = 0, warmed = 0;

        for (Auction a : repo.findByStatus("PENDING")) {
            if (a.getStartTime() != null) {
                redis.opsForZSet().add(SCHEDULE_OPEN, a.getAuctionId(),
                        Instant.parse(a.getStartTime()).toEpochMilli());
                openCount++;
            }
        }
        for (Auction a : repo.findByStatus("OPEN")) {
            if (a.getEndTime() != null) {
                redis.opsForZSet().add(SCHEDULE_CLOSE, a.getAuctionId(),
                        Instant.parse(a.getEndTime()).toEpochMilli());
                closeCount++;
            }
            if (!Boolean.TRUE.equals(redis.hasKey("auction:" + a.getAuctionId()))) {
                seedRedisCache(a);
                warmed++;
            }
        }

        log.info("rebuilt auction schedules: {} pending opens, {} pending closes, {} caches warmed",
                openCount, closeCount, warmed);
    }
}
