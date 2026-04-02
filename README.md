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
                                         │
                              Redis Streams (bid_placed)
                               ┌─────────┴──────────┐
                               ▼                    ▼
                          bid-service        notification-service
                            :8084                 :8080
                          (DynamoDB)           (STOMP/WS)

                   Redis Streams (auction:closed)
                               │
                               ▼
                        payment-service
                            :8085
                          (PostgreSQL)
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

## Quick Start

```bash
docker-compose up --build
bash scripts/smoke-test.sh   # wait ~30s for services to be healthy
```

## Storage

| Store | Used By | Purpose |
|-------|---------|---------|
| PostgreSQL | user, shop, payment | ACID transactions, relational data |
| DynamoDB | auction, bid | Fast key-value, auction state, bid history |
| Redis | auction | Hot-path cache, sorted sets, streams, distributed locks |

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

`AuctionCloser` scans all `OPEN` auctions every second and closes those whose `endTime` has passed.

### Redis Data Structures

Every open auction maintains two Redis keys.

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
        ├── 3. Redisson lock acquired    lock:auction:{id}
        │
        ├── 4. Lua script (atomic)       place_bid.lua runs on Redis server
        │         ├── check status = OPEN
        │         ├── check amount > current_highest (floor)
        │         ├── check amount <= max_price (if set)
        │         ├── if winners full: evict floor holder from ZSET
        │         ├── ZADD new winner to ZSET
        │         ├── recalculate floor, update hash (HMSET)
        │         └── return {version, bidCount, prevBidder, prevAmount, newFloor, topBidder}
        │
        ├── 5. Read full ZSET (inside lock)   consistent snapshot of current winners
        │
        ├── 6. Redisson lock released
        │
        ├── 7. DynamoDB update           persist currentHighest, highestBidder, winners map, version
        │
        └── 8. Publish BidPlacedEvent    → bid_placed Redis Stream
```

### Why Lua and Redisson Together

The Lua script guarantees that the Redis read-validate-write sequence is atomic on the Redis server side, regardless of how many `auction-service` instances are running. However, the DynamoDB write after the Lua script is not part of Redis and cannot be included in a Lua script. The Redisson lock serialises concurrent bids for the same auction, protecting both the Lua execution order and the subsequent DynamoDB persistence.

### Multi-Winner Auctions

When `quantity > 1`, multiple buyers can win the same auction. The Sorted Set holds up to `quantity` entries at any time. A new bid enters the set if:
- The set has fewer entries than `quantity` (slots still available), and the bid exceeds `startingBid`
- The set is full and the bid exceeds the current floor score (the lowest winner is evicted)

When `quantity = 1`, behaviour is identical to a standard single-winner auction.

### Cache Rebuild (Cache Miss Handling)

If the Redis hash key is missing (Redis restart or eviction), `ensureRedisCached()` rebuilds it from DynamoDB before the bid is processed. A Redisson lock (`lock:rebuild:{id}`) prevents a burst of concurrent requests from all hitting DynamoDB simultaneously. The lock uses Double-Checked Locking: the cache key is checked again inside the lock before rebuilding.

Note: the Sorted Set (`auction:{id}:winners`) cannot be fully rebuilt from DynamoDB after a Redis restart, because DynamoDB stores only the snapshot from the last successful bid. The `winners` map in DynamoDB reflects the state at the last bid and is used as a fallback at close time.

### Auction Close Flow

```
AuctionCloser detects endTime has passed
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
        └── 5. Delete Redis hash and ZSET keys
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

### Consumer Group Reliability

Both `BidEventConsumer` and `AuctionClosedConsumer` create their consumer groups with `MKSTREAM = true`. This ensures group creation succeeds even when bid service starts before any message has been published to the stream.

Failed messages remain in the PEL (Pending Entry List) and are not automatically re-delivered on restart. Full PEL recovery would require `XAUTOCLAIM`. This is a known limitation acceptable for this project.

---

## Inter-Service Events

| Stream | Publisher | Consumers | Payload |
|--------|-----------|-----------|---------|
| `bid_placed` | auction-service | bid-service, notification-service | auctionId, bidId, userId, amount, previousBidder, previousHighest |
| `auction:closed` | auction-service | payment-service, bid-service | auctionId, winners (Map of bidderId to amount), itemId, shopId |
| `payment:processed` | payment-service | notification-service | auctionId, winnerId, amount |
| `payment:failed` | payment-service | notification-service | auctionId, winnerId, reason |
| `payment:dlq` | payment-service | (manual replay) | failed payment records |

---

## Key Technical Decisions

### Java 21 Virtual Threads

All services enable `spring.threads.virtual.enabled=true`. Redis Stream consumers run in dedicated virtual threads. This avoids blocking carrier threads during `XREAD` blocking calls.

### Concurrency Strategy Selection

The active strategy is `PessimisticStrategy`. Two alternatives exist in `concurrency/experimental/` for reference.

| Strategy | Mechanism | Active |
|----------|-----------|--------|
| Pessimistic | Redisson RLock + Lua script | Yes (default) |
| Optimistic | Redis WATCH/MULTI/EXEC, 3 retries | No |
| Queue | Per-auction LinkedBlockingQueue + virtual thread | No |

The Queue strategy is not suitable for multi-instance deployments because the in-memory queue is not shared across instances.

### DynamoDB Winners Persistence

On every accepted bid, the full winners map (bidderId to amount) is written to DynamoDB inside the Redisson lock, using a consistent ZSET snapshot taken before the lock is released. This ensures that if the Redis Sorted Set is lost before an auction closes, the complete winners list can be recovered from DynamoDB.

### Event-First Close Ordering

Auction close publishes the event before updating DynamoDB status. This trades the possibility of a duplicate event (handled by idempotent consumers) for the guarantee that a failed publish never leaves an auction in an unrecoverable closed-but-unpaid state.

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
