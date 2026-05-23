# Dropbid Java — Collectible Toy Auction Platform

A real-time auction platform for secondhand collectible toys.
Sellers list items with configurable start times, durations, and winner counts. Buyers compete through a high-concurrency bidding system backed by Redis and DynamoDB.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      Client (Browser)                        │
│             REST · STOMP/WebSocket · SSE                     │
└───────────────────────┬─────────────────────────────────────┘
                        │
        ┌───────────────┼───────────────────┐
        ▼               ▼                   ▼
  user-service     shop-service      auction-service
    :8082            :8083               :8081
  (PostgreSQL)     (PostgreSQL)    (DynamoDB + Redis)
      │                │                   │
      │  user:updated  │  item:updated     │  bid_placed
      └───────┐  ┌─────┘           ┌───────┴──────────┐
              ▼  ▼                 ▼                   ▼
         query-service        bid-service      notification-service
            :8086               :8084               :8080
          (PostgreSQL)        (DynamoDB)          (STOMP/WS)

                   Redis Streams (auction:closed)
                        ┌──────┴──────┐
                        ▼             ▼
                 payment-service  query-service
                    :8085           :8086
                  (PostgreSQL)

            Redis Streams (payment:processed / payment:failed)
                        │
                        ▼
                  query-service
                     :8086
```

## Service Ports

| Service              | Port |
|----------------------|------|
| notification-service | 8080 |
| auction-service      | 8081 |
| user-service         | 8082 |
| shop-service         | 8083 |
| bid-service          | 8084 |
| payment-service      | 8085 |
| query-service        | 8086 |

## Quick Start

```bash
docker-compose up --build
bash scripts/smoke-test.sh   # wait ~30s for services to be healthy
```

## Storage

| Store | Used By | Purpose |
|-------|---------|---------|
| PostgreSQL | user, shop, payment, query | ACID transactions, relational data, CQRS read store |
| DynamoDB | auction, bid | Fast key-value, auction state, bid history |
| Redis | auction, user, shop, query | Hot-path cache, sorted sets, streams, Lua atomic operations, scheduling |

---

## Auction Service

### Auction Lifecycle

```
        createAuction()
              │
    startTime in future?
       │              │
      YES             NO
       │              │
   PENDING          OPEN ──────────────────────────────┐
       │              │                                 │
  AuctionOpener   accepts bids                    endTime passed
  (every 1s)          │                                 │
       │         AuctionCloser                    AuctionCloser
       └──OPEN ──(every 1s) ──────────────────► CLOSED
```

An auction is created as `OPEN` when no `startTime` is provided (or the provided time has already passed). When a future `startTime` is given, the auction is created as `PENDING` and opened automatically by `AuctionOpener` when the time arrives.

Both schedulers use Redis Sorted Sets (`auction:schedule:open` and `auction:schedule:close`) to efficiently find auctions that are due. Each auction's start/end time is stored as the score, and the scheduler calls `ZRANGEBYSCORE` to fetch only the auctions whose time has passed — avoiding a full DynamoDB table scan. On service restart, `ScheduleRecovery` rebuilds these sorted sets from DynamoDB.

### Redis Data Structures

Two global Sorted Sets manage auction scheduling:

| Key | Score | Purpose |
|-----|-------|---------|
| `auction:schedule:open` | `startTime` epoch ms | PENDING auctions awaiting open |
| `auction:schedule:close` | `endTime` epoch ms | OPEN auctions awaiting close |

Every open auction maintains two additional Redis keys.

**Hash** `auction:{id}` — live bid state, read on every bid:

| Field | Description |
|-------|-------------|
| `status` | `OPEN` or `CLOSED` |
| `current_highest` | Minimum bid needed to enter the winners set |
| `highest_bidder` | Bidder with the highest amount |
| `quantity` | Number of winner slots |
| `bid_count` | Total accepted bids |
| `version` | Incremented on each accepted bid |
| `seller_id` | Used for seller-cannot-bid check without a DynamoDB read |
| `max_price` | Bid ceiling (item original price); `0` means no ceiling |

**Sorted Set** `auction:{id}:winners` — current winner slots:

```
score=350  userA   ← top bidder
score=280  userB
score=200  userC   ← floor (lowest winning bid, quantity=3)
```

`current_highest` in the hash always mirrors the floor score when all slots are filled, so new bids must exceed it to displace the current floor holder.

### Bid Placement Flow

```
PUT /auctions/{id}/bid
        │
        ├── 1. ensureRedisCached()       check hash key exists; rebuild from DynamoDB if missing
        │         (Double-Checked Locking via Redisson rebuild lock)
        │
        ├── 2. Seller check              read seller_id from Redis hash; reject if bidderId matches
        │
        ├── 3. Lua script (atomic)       place_bid.lua runs on Redis server (single-threaded)
        │         ├── check status = OPEN
        │         ├── check amount > current_highest (floor)
        │         ├── check amount <= max_price (if set)
        │         ├── if winners full: evict floor holder from ZSET
        │         ├── ZADD new winner to ZSET
        │         ├── recalculate floor, update hash (HMSET)
        │         ├── ZRANGE winners snapshot (atomic, no gap)
        │         └── return {version, bidCount, prevBidder, prevAmount, newFloor, topBidder, winners...}
        │
        ├── 4. Async DynamoDB snapshot        fire-and-forget; conditional write (version < :v) discards stale
        │
        └── 5. Publish BidPlacedEvent    → bid_placed Redis Stream
```

### Why No Distributed Lock

Redis executes Lua scripts atomically in its single-threaded event loop. Concurrent bids from multiple `auction-service` instances are queued at the Redis server and processed one at a time — the script IS the serialization mechanism. No external lock (Redisson, etc.) is needed because there is no gap between operations within the script.

The DynamoDB write uses a conditional expression (`version < :newVersion`) to handle out-of-order arrivals from multiple instances. If a stale write arrives after a newer one, the condition fails and the write is silently discarded.

### Multi-Winner Auctions

When `quantity > 1`, multiple buyers can win the same auction. The Sorted Set holds up to `quantity` entries at any time. A new bid enters the set if:
- The set has fewer entries than `quantity` (slots still available), and the bid exceeds `startingBid`
- The set is full and the bid exceeds the current floor score (the lowest winner is evicted)

When `quantity = 1`, behaviour is identical to a standard single-winner auction.

### Cache Rebuild (Cache Miss Handling)

If the Redis hash key is missing (Redis restart or eviction), `ensureRedisCached()` rebuilds it from DynamoDB before the bid is processed. A Redisson lock (`lock:rebuild:{id}`) prevents a burst of concurrent requests from all hitting DynamoDB simultaneously (thundering herd protection). The lock uses Double-Checked Locking: the cache key is checked again inside the lock before rebuilding.

Note: the Sorted Set (`auction:{id}:winners`) cannot be fully rebuilt from DynamoDB after a Redis restart, because DynamoDB stores only the snapshot from the last successful bid. The `winners` map in DynamoDB reflects the state at the last bid and is used as a fallback at close time.

### Auction Close Flow

```
AuctionCloser: ZRANGEBYSCORE auction:schedule:close 0 <now>
        │
        ├── 1. Read winners ZSET from Redis
        │
        ├── 2. If ZSET empty, fall back to DynamoDB winners map
        │         If winners map also empty, fall back to DynamoDB highestBidder
        │
        ├── 3. Publish AuctionClosedEvent (winners map)   ← BEFORE marking CLOSED
        │
        ├── 4. DynamoDB: status = CLOSED
        │
        ├── 5. Delete Redis hash and ZSET keys
        │
        └── 6. ZREM auction:schedule:close {auctionId}
```

The event is published **before** the DynamoDB status update. If the publish fails, DynamoDB remains `OPEN` and the scheduler retries on the next tick. If the publish succeeds but the DynamoDB write fails, downstream services receive a duplicate event on the next retry. Consumers are idempotent by design (via Redis Streams consumer group ack), so duplicate events are handled correctly.

### Validation Rules

| Rule | Where enforced |
|------|----------------|
| `endTime` must be in the future | `createAuction()` |
| `startTime` must be before `endTime` | `createAuction()` |
| `maxPrice` must exceed `startingBid` | `createAuction()` |
| Bid must exceed current floor | Lua script |
| Bid must not exceed `maxPrice` | Lua script |
| Seller cannot bid on own auction | `placeBid()`, reads `seller_id` from Redis hash |

---

## Bid Service

### Design

Bid service is purely event-driven. It has no write endpoints. All bid records are created and updated by consuming Redis Streams.

```
bid_placed stream ──► BidEventConsumer ──► recordBid()
                                                │
                                         mark own previous ACTIVE bids as OUTBID
                                         mark knocked-out bidder's ACTIVE bid as OUTBID
                                         save new bid with status ACTIVE

auction:closed stream ──► AuctionClosedConsumer ──► markWon()
                                                          │
                                               mark winners' ACTIVE bids as WON
```

### Bid Status Transitions

```
            new bid accepted
                  │
               ACTIVE
              /       \
    outbid by          auction
    higher bid          closes
        │                  │
     OUTBID              WON
```

A bid moves from `ACTIVE` to `OUTBID` when either:
- A different bidder's higher bid displaces it from the winners set (floor eviction)
- The same bidder raises their own bid, making their previous record stale

A bid moves from `ACTIVE` to `WON` when the auction closes and the bidder appears in the `winners` map of `AuctionClosedEvent`.

### Self-Raise Handling

When the same bidder raises their own bid, the Lua script updates their score in the ZSET but may not always set `previousBidder` to themselves (only when they are the current floor holder). To prevent a single bidder from accumulating multiple `ACTIVE` records, `recordBid()` always invalidates the current bidder's own previous `ACTIVE` bids before recording the new one.

---

## Inter-Service Events

| Stream | Publisher | Consumers | Payload |
|--------|-----------|-----------|---------|
| `bid_placed` | auction-service | bid-service, notification-service, query-service | auctionId, bidId, sellerId, userId, amount, previousBidder, previousHighest |
| `auction:closed` | auction-service | payment-service, bid-service, query-service | auctionId, winners (Map of bidderId to amount), itemId, shopId |
| `payment:processed` | payment-service | query-service | auctionId, winnerId, amount |
| `payment:failed` | payment-service | query-service | auctionId, winnerId, reason |
| `{stream}:dlq` | ResilientStreamConsumer | (manual replay / alerting) | messages that failed processing after 5 retries |
| `user:updated` | user-service | query-service | userId, username, role |
| `item:updated` | shop-service | query-service | itemId, shopId, title, imageUrl, series, condition |

### Consumer Reliability (ResilientStreamConsumer)

All stream consumers across every service extend a shared base class (`shared/streaming/ResilientStreamConsumer`) that provides:

1. **Consume loop** — virtual thread, XREADGROUP with configurable batch size and block timeout
2. **ACK on success** — messages are acknowledged only after `handleMessage()` completes without exception
3. **PEL reclaim** — a dedicated reclaim thread scans every 30 seconds for unacknowledged messages; timed-out entries are re-delivered automatically
4. **DLQ (Dead Letter Queue)** — messages that fail after 5 retries are moved to `{stream}:dlq` and acknowledged, preventing infinite retry loops
5. **MKSTREAM** — consumer groups are created with `MKSTREAM = true`, so group creation succeeds even before any message is published

Subclasses only implement `handleMessage()` and three config methods (stream/group/consumerName). The payment-service overrides the default single-consumer pattern with N parallel workers for higher throughput.

---

## Query Service (CQRS Read Store)

Query service provides cross-service read queries that would otherwise require API-layer aggregation across multiple services. It is purely event-driven on the write side and exposes read-only REST endpoints.

### Data Model

| Table | Granularity | Updated By |
|-------|-------------|------------|
| `auction_summary` | One row per auction | `bid_placed`, `auction:closed` |
| `bid_activity` | One row per (auction, bidder) | `bid_placed`, `auction:closed`, `payment:*` |
| `user_lookup` | One row per user | `user:updated` |
| `item_lookup` | One row per item | `item:updated` |

### Endpoints

| Endpoint | Description | Auth |
|----------|-------------|------|
| `GET /query/my/bids?status=WON&page=0&size=20` | Buyer's bid history with item names and images | BUYER |
| `GET /query/seller/auctions?status=CLOSED` | Seller's auction list with item details | SELLER |
| `GET /query/seller/auctions/{id}/bids` | All bids on a seller's auction with bidder names | SELLER |
| `GET /query/auctions?sort=bidCount&status=OPEN` | Public auction listing with item details | None |

### Data Enrichment

Responses are enriched with user names and item details from the lookup tables. Three layers ensure lookup data is available:

1. **Event-driven** (primary): `user:updated` and `item:updated` events keep lookup tables current.
2. **Cold-start sync** (startup): On boot, query-service pulls all users and items from `GET /internal/users` and `GET /internal/items` to backfill lookup tables.
3. **On-demand fallback** (per-request): If a userId or itemId is missing from the lookup table at query time, the enrichment service fetches it from the source service, caches it locally, and includes it in the response.

---

## Key Technical Decisions

### Java 21 Virtual Threads

All services enable `spring.threads.virtual.enabled=true`. Redis Stream consumers run in dedicated virtual threads. This avoids blocking carrier threads during `XREAD` blocking calls.

### Concurrency Strategy Selection

The active strategy is `PessimisticStrategy` (atomic Lua script, no distributed lock). Two alternatives exist in `concurrency/experimental/` for reference.

| Strategy | Mechanism | Active |
|----------|-----------|--------|
| Pessimistic | Atomic Lua script (Redis single-threaded execution) | Yes (default) |
| Optimistic | Redis WATCH/MULTI/EXEC, 3 retries | No |
| Queue | Per-auction LinkedBlockingQueue + virtual thread | No |

The Queue strategy is not suitable for multi-instance deployments because the in-memory queue is not shared across instances.

### DynamoDB Winners Persistence

On every accepted bid, the full winners map (bidderId → amount) is written asynchronously to DynamoDB via `CompletableFuture.runAsync` (fire-and-forget). The write uses a conditional expression (`version < :v`) so out-of-order arrivals are silently discarded. The winners snapshot is captured atomically inside the Lua script, guaranteeing consistency. This async write is cheap insurance — if Redis loses data before auction close, the winners list can be partially recovered from the last successful DynamoDB snapshot.

### Event-First Close Ordering

Auction close publishes the event before updating DynamoDB status. This trades the possibility of a duplicate event (handled by idempotent consumers) for the guarantee that a failed publish never leaves an auction in an unrecoverable closed-but-unpaid state.

---

## Load Testing

A load test suite validates bid latency, system throughput, resource usage, and data consistency under concurrent load. See [`loadtest/PLAN.md`](loadtest/PLAN.md) for the full test design.

```bash
bash loadtest/run.sh          # run all tests
bash loadtest/run.sh test2    # run a single test
```

### Test Scenarios

| Test | What it measures |
|------|-----------------|
| Test 1: Single auction, 10→25→50 concurrent bidders | Single-auction throughput under concurrency |
| Test 2: 20 auctions, 50 concurrent bidders, 30s | Real-world throughput (p50/p95/p99) |
| Test 3: 10 auctions, bid→close→payment lifecycle | Event pipeline propagation and end-to-end consistency |

### What's Verified

**Performance**: Per-request latency percentiles, bid duration (via Micrometer), throughput.

**Resources**: CPU and memory per container sampled every 2s via `docker stats`. Peak values reported per test.

**Consistency** (after each test):
- Redis `bid_count` and `current_highest` match DynamoDB
- Redis winners ZSET members match DynamoDB winners map
- Winners count never exceeds auction `quantity`
- After auction close: all bids transition from ACTIVE to WON/OUTBID, payments are created, Redis keys are cleaned up, stream consumer lag reaches zero

---

## Build Locally (without Docker)

```bash
# Requires Java 21 + Maven 3.9+
mvn install -DskipTests

# Start infrastructure
docker-compose up postgres dynamodb-local redis init-dynamo -d

# Run individual service
cd auction-service && mvn spring-boot:run
```
