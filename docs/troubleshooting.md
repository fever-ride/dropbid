# Troubleshooting Log

Problems encountered during development, how they were diagnosed, and how they were fixed.

---

## 1. Docker Build: Maven Multi-Module Resolution Failure

**Symptom**: Docker build fails with `Child module does not exist`.

**Cause**: The root `pom.xml` declares 8 modules, but each service's Dockerfile only COPYs its own and shared's `pom.xml`. Maven's reactor can't find the other modules' poms.

**Fix**: COPY all module pom files during the dependency download stage:

```dockerfile
COPY pom.xml .
COPY shared/pom.xml shared/pom.xml
COPY auction-service/pom.xml auction-service/pom.xml
COPY bid-service/pom.xml bid-service/pom.xml
# ... all modules
```

---

## 2. Flyway Checksum Mismatch

**Symptom**: A service fails to start with a Flyway checksum validation error.

**Cause**: Four PostgreSQL services (user, shop, payment, query) share one database. They all default to the `flyway_schema_history` table, so one service's migration records interfere with another's checksum validation.

**Fix**: Give each service its own Flyway history table:

```yaml
spring:
  flyway:
    table: user_flyway_history      # shop_flyway_history, payment_flyway_history, etc.
    baseline-on-migrate: true
```

---

## 3. JwtUtil Bean Not Found

**Symptom**: Multiple services fail at startup with `No qualifying bean of type 'JwtUtil'`.

**Cause**: `JwtUtil` is annotated `@Component` under `com.dropbid.shared`, but `@SpringBootApplication` only scans its own package (e.g. `com.dropbid.auction`) by default.

**Fix**: Add `scanBasePackages` to every service's Application class:

```java
@SpringBootApplication(scanBasePackages = {"com.dropbid.auction", "com.dropbid.shared"})
```

---

## 4. Mockito Cannot Mock Concrete Classes (Java 25)

**Symptom**: `mock(BidRepository.class)` throws `Mockito cannot mock this class`.

**Cause**: Two issues stacked.

1. `BidRepository`'s constructor calls `enhanced.table()`, which NPEs when passed `null`. Subclass-based mocking can't bypass this.
2. The project's Mockito 5.7.0 + ByteBuddy 1.14.13 don't support Java 25 module restrictions. Inline mock creation fails.

**Fix**:

Upgrade dependencies in the parent pom:
```xml
<mockito.version>5.17.0</mockito.version>
<byte-buddy.version>1.17.5</byte-buddy.version>
```

Extract interfaces (`BidStore`, `AuctionStore`) from the DynamoDB repository classes. Service layer depends on the interface. Tests use anonymous in-memory implementations instead of Mockito:

```java
BidStore repo = new BidStore() {
    @Override public void save(Bid bid) { store.add(bid); }
    @Override public List<Bid> findByAuctionId(String id) {
        return store.stream().filter(b -> b.getAuctionId().equals(id)).toList();
    }
    // ...
};
```

Redis/Redisson infrastructure classes still use Mockito mocks (works after the upgrade).

---

## 5. Port 8080 Conflict

**Symptom**: Service fails to start because the port is already in use.

**Diagnosis**: `lsof -i :8080` revealed an unrelated Node process.

**Fix**: `kill <PID>` to free the port. In production, configure `server.port` to avoid conflicts.

---

## 6. Flyway baseline-on-migrate Skips Actual Migration

**Symptom**: user-service starts with `Schema-validation: missing table [users]`. Flyway logs say "no migration necessary".

**Cause**: Multiple services share one PostgreSQL database. When shop-service starts first and creates tables, user-service sees a non-empty schema. `baseline-on-migrate` creates a baseline record at version 1. Flyway thinks V1 has run, but user-service's V1 migration never actually executed.

**Fix**: Set `baseline-version: 0` in all services. The baseline record is created at version 0, so V1 still runs:

```yaml
spring:
  flyway:
    baseline-on-migrate: true
    baseline-version: 0
```

---

## 7. Docker Health Check Fails (No curl in JRE Alpine)

**Symptom**: Services start normally but Docker marks them `unhealthy`, blocking dependent services.

**Cause**: `eclipse-temurin:21-jre-alpine` doesn't include `curl`. The health check command returns exit code 127 (command not found).

**Fix**: Use `wget` (bundled with Alpine) and add `start_period` for Java startup time:

```yaml
healthcheck:
  test: ["CMD-SHELL", "wget -qO- http://localhost:8082/actuator/health || exit 1"]
  start_period: 30s
```

---

## 8. DynamoDB Enhanced Client Missing GSI Annotations

**Symptom**: auction-service throws `Attempt to execute an operation that requires a secondary index without defining the index attributes in the table metadata`.

**Cause**: The DynamoDB table GSIs were created successfully via `init-dynamo.sh`, but the Java model classes (`Auction.java`, `Bid.java`) lacked `@DynamoDbSecondaryPartitionKey` and `@DynamoDbSecondarySortKey` annotations. The Enhanced Client needs these to build index queries.

**Fix**: Add GSI annotations to the corresponding getters:

```java
@DynamoDbSecondaryPartitionKey(indexNames = "status-index")
public String getStatus() { return status; }

@DynamoDbSecondarySortKey(indexNames = {"auction-index", "bidder-index"})
public String getCreatedAt() { return createdAt; }
```

---

## 9. DynamoDB Local Health Check (/shell/ Endpoint Removed)

**Symptom**: `dynamodb-local` container stays in `health: starting` indefinitely, blocking `init-dynamo`.

**Cause**: Newer DynamoDB Local images removed the `/shell/` endpoint. The original health check `curl -f http://localhost:8000/shell/` always fails.

**Fix**: Check for DynamoDB's authentication error response instead (proves the service is up):

```yaml
healthcheck:
  test: ["CMD-SHELL", "curl -s http://localhost:8000 | grep AuthenticationToken"]
```

---

## 10. RandomGenerator Algorithm Unavailable (Java 21 JRE)

**Symptom**: payment-service throws `No implementation of the random number generator algorithm "L32X64MixRandom" is available`.

**Cause**: `RandomGenerator.getDefault()` selects an algorithm that isn't available in some JRE builds.

**Fix**: Use `ThreadLocalRandom` instead, which is available in all JREs:

```java
ThreadLocalRandom.current().nextInt(10)
```

---

## 11. Bash `set -e` Conflicts with Arithmetic

**Symptom**: smoke-test.sh exits immediately after the first `[PASS]`. No further tests run.

**Cause**: Under `set -e`, `((PASS++))` when PASS=0 evaluates to `((0++))`, which returns 0 (falsy). Bash treats this as a command failure and triggers `set -e` exit.

**Fix**: Append `|| true` to all arithmetic operations:

```bash
((PASS++)) || true
((FAIL++)) || true
```

---

## 12. Bash Subshells Don't Inherit Array Variables

**Symptom**: 90%+ of load test requests return HTTP 403 (missing Authorization header).

**Cause**: `declare -a BUYER_TOKENS=(...)` declared inside a function is not inherited by `( ) &` background subshells. `${BUYER_TOKENS[$idx]}` evaluates to empty, so curl sends no Authorization header. The server returns 403.

**Diagnosis**: Compared concurrency=10 (66 successes) with concurrency=50 (18 successes). Success counts were far lower than expected. Error breakdown showed all 403s, not 409 (lock contention) or 400 (amount too low). Manual single-request testing with a token succeeded, confirming the token-passing mechanism was the problem.

**Fix**: Write tokens to a temp file. Subshells read by line number via `sed`:

```bash
# write
echo "$tok" >> "$TOKENS_FILE"

# read inside subshell
token=$(sed -n "${line_num}p" "$TOKENS_FILE")
```

---

## 13. macOS Default Bash Doesn't Support `mapfile`

**Symptom**: Load test fails after Test 2 with `mapfile: command not found`.

**Cause**: macOS ships bash 3.2. `mapfile` (aka `readarray`) requires bash 4.0+.

**Fix**: Replace with a `while read` loop:

```bash
# instead of: mapfile -t AUCTION_IDS < "$AUCTIONS_FILE"
local AUCTION_IDS=()
while IFS= read -r line; do AUCTION_IDS+=("$line"); done < "$AUCTIONS_FILE"
```

---

## 14. Hot Auction Bottleneck — Removing the Distributed Lock

**Symptom**: Under load test (Test 1: single auction, 50 concurrent bidders), p50 latency reaches 272ms and only 21 out of 1585 requests succeed within 15 seconds. Throughput collapses for a single hot auction.

**Diagnosis — three stages of understanding**:

### Stage 1: Identifying the bottleneck

We originally designed the concurrency strategy around **scenario A** (many auctions running in parallel). The per-auction Redisson lock (`lock:auction:{id}`) ensures different auctions never block each other — validated by Test 2 (20 auctions, even distribution, 100% consistency).

Load testing exposed **scenario B** (many bidders on the same auction). The lock hold window was:

```
tryLock → Lua script (~1ms) → ZRANGEWITHSCORES (~0.5ms) → repo.update(DynamoDB, ~15-20ms) → unlock
```

Theoretical ceiling: ~50 bids/s per auction.

### Stage 2: Understanding what the lock actually protects

Initial assumption: the lock protects the DynamoDB write.

Actual finding: the lock protects the **gap between the Lua script and the subsequent ZRANGEWITHSCORES**. Without the lock, a concurrent bid could modify the winners ZSET between the Lua write and the ZRANGE read, producing an inconsistent snapshot.

The DynamoDB write happened to be inside the lock, but that was incidental — it inflated hold time without being the protected resource.

### Stage 3: Why this differs from the "黑马点评" pattern

The original design was inspired by the Redis + distributed lock pattern (e.g. 黑马点评's Redisson-based one-user-one-order). But our architecture is fundamentally different:

| | 黑马点评 | Our project |
|--|---------|-------------|
| Source of truth | MySQL | Redis |
| Redis role | Cache + lock coordination | **Runtime state authority** |
| Why lock is needed | MySQL can't do atomic check-then-act | Redis Lua CAN do atomic check-then-act |

In 黑马点评, the lock serializes access to MySQL operations that aren't individually atomic. In our system, the Lua script already provides atomicity — the lock is redundant once the ZRANGE is moved inside the script.

### Fix (implemented)

Moved the ZRANGE winners snapshot inside the Lua script, making the entire read-validate-write-snapshot operation atomic in a single Redis call. This eliminates the need for the Redisson lock entirely.

```
Before: lock → Lua → ZRANGE → DynamoDB → unlock   (~22ms hold, ~50 bid/s)
After:  Lua (includes ZRANGE) → DynamoDB            (no lock, ~2ms Lua, thousands bid/s)
```

For multi-instance safety, DynamoDB writes now use a conditional expression (`version < :v`) to prevent out-of-order overwrites. Stale writes are silently discarded.

**Key insight**: Redis single-threaded execution model IS the lock. Adding Redisson on top is locking a system that's already serialized. The Lua script on a single Redis instance provides stronger guarantees than a distributed lock — it's not just mutual exclusion, it's true atomic execution with no gap between operations.

**Consistency guarantees preserved**:
1. Redis Lua atomicity ensures bid correctness (no race conditions)
2. DynamoDB conditional write ensures persistence never regresses
3. `closeAuction()` reads final state from Redis (authoritative) before archiving to DynamoDB
4. `ensureRedisCached()` rebuild lock (separate concern) still uses Redisson — protects cache rebuild from thundering herd, not bid logic

---

## 15. Bid Service Consolidation — Removing a Service That Paid No Rent

### Background

The system originally included a dedicated `bid-service` modelled after a Go reference project. In the Go version, `bid-service` was the only place to query bid history — it consumed `bid_placed` events and exposed REST endpoints used directly by the frontend. The microservice was clearly load-bearing there.

When we added `query-service` as a CQRS read model, the situation changed without anyone noticing at first.

### Diagnosis

A side-by-side comparison of what each service actually did:

| Capability | bid-service | query-service |
|---|---|---|
| Record bids | Append-only DynamoDB row per bid | Upsert PostgreSQL `BidActivity` row per (bidder, auction) |
| Mark WON/OUTBID on close | Yes | Yes |
| "My bids" query | `/bids/me` | `/query/my/bids` (richer: includes item name, image) |
| "Auction bid list" query | `/bids/auction/{id}` | `/query/seller/auctions/{id}/bids` |

Searching the codebase for callers of bid-service's REST endpoints found only two files: `smoke-test.sh` and `loadtest/run.sh`. No production service and no frontend called them.

**The key asymmetry**: the Go reference project had no `query-service`, so `bid-service` was the only read model. Our Java project added `query-service` — which made `bid-service`'s read path completely redundant. Its only surviving responsibility was the write path: consuming `bid_placed` events and writing rows to a DynamoDB `Bids` table.

A write-only service that maintains a table nobody queries is operational overhead without product value.

### Decision

Merge `bid-service` into `auction-service`. Instead of routing bid writes through Redis Streams (publish → consume → write), write bid history records directly inside `AuctionService.placeBid()` and `AuctionService.closeAuction()`. This removes one deployment unit, one consumer group, one DynamoDB table owner, and eliminates the event round-trip.

The DynamoDB `Bids` table itself was retained — the append-only per-bid audit trail still has value for debugging and dispute resolution. Only the service boundary moved.

**Interview angle**: this is a better story than "I split everything into microservices." Recognising when a service boundary no longer maps to a real bounded context and collapsing it demonstrates architectural judgment rather than reflexive decomposition.

### Issue Encountered During Merge: Race Condition Between `recordBidHistory` and `markBidsWon`

The initial implementation made both writes fire-and-forget async:

```java
// in placeBid()
CompletableFuture.runAsync(() -> recordBidHistory(...));  // writes ACTIVE bid to DynamoDB

// in closeAuction()
CompletableFuture.runAsync(() -> markBidsWon(...));       // reads ACTIVE bids, marks WON
```

This introduced a race: if `closeAuction()` fired immediately after `placeBid()` returned, `markBidsWon` could execute before `recordBidHistory` had finished writing the last bid record. That bid would stay ACTIVE permanently — never promoted to WON.

The root cause is that `markBidsWon` depends on the Bids table being complete, but there was no happens-before guarantee between the two async tasks.

**Fix**: make `recordBidHistory` synchronous within `placeBid()`. The bid is already accepted atomically in Redis before this point; the DynamoDB history write is not on the latency-critical path, and adding ~5ms is an acceptable trade-off. Once `placeBid()` returns, the bid record is guaranteed to be in DynamoDB. Any subsequent `closeAuction()` call will see a complete set of records.

```java
// After fix — synchronous, guaranteed to complete before placeBid() returns
try {
    recordBidHistory(auctionId, bidId, bidderId, amount, result.previousBidder(), bidTime);
} catch (Exception e) {
    log.warn("bid history write failed for auction {}: {}", auctionId, e.getMessage());
}
```

A narrow residual race remains — `closeAuction()` could theoretically begin while a concurrent `placeBid()` is executing its synchronous DynamoDB write. For production, this would require a close-phase fence (e.g. brief lock during final-bid window). At interview scale it is acceptable and worth acknowledging honestly.

### Issue Encountered During Merge: `AuctionServiceTest` Constructor Mismatch

Adding `BidStore` to `AuctionService`'s constructor broke the existing unit test, which constructed the service with five arguments:

```java
// Before merge — compiled fine
service = new AuctionService(repo, redis, redisson, publisher, strategyManager);

// After merge — compile error: no matching constructor
service = new AuctionService(repo, redis, redisson, publisher, strategyManager);
```

**Fix**: add a `BidStore` mock to the test setup and pass it as the second constructor argument. The existing test scenarios were unaffected — none of them exercise bid history paths, so the mock needed no stubs.

```java
bidStore = mock(BidStore.class);
service = new AuctionService(repo, bidStore, redis, redisson, publisher, strategyManager);
```

**Learning**: when a service method's signature changes, the compiler catches it everywhere except in tests that construct the class directly with `new`. Integration-style tests using Spring context injection would have caught this automatically. It is worth keeping at least one Spring-wired smoke test alongside unit tests that construct services manually.

### Subsequent Finding: `markBidsWon` Was Unnecessary

During review, `markBidsWon` — the function that read all ACTIVE bids for an auction and updated winners to WON status on close — was identified as redundant:

- The `Auction` record already stores a `winners` map (`bidderId → amount`) written by `closeAuction()`.
- `query-service` already tracks WON/OUTBID per bidder via its `BidActivity` table, which is the actual source for any "my winning bids" UI.

The Bids table in auction-service is an audit trail, not a query model. For audit purposes, knowing *who bid what and when* is sufficient — whether they won is derivable from `Auction.winners`. Maintaining WON status separately was data duplication with no consumer.

Removing `markBidsWon` also eliminated the race condition between `recordBidHistory` and `markBidsWon` entirely (making the synchronous fix above simpler to reason about), and simplified `closeAuction()` by removing an async side-effect.

**Learning**: before writing a status-update routine, ask: who reads this status, and could they get the same information from an authoritative source that already exists? In a CQRS architecture with a dedicated read model, writing derived state into the write-side store is usually a sign that responsibilities have blurred.

### Issue: `markBidsOutbid` Did a Full Auction Scan

The function that marks a bidder's previous bids as OUTBID was implemented using `auction-index`:

```java
// Original — reads ALL bids for the auction, then filters by bidderId
bidStore.findByAuctionId(auctionId).stream()
        .filter(b -> b.getBidderId().equals(bidderId) && "ACTIVE".equals(b.getStatus()))
        ...
```

This GSI query returns every bid in the auction. As the auction accumulates bids, the scan grows linearly. Since `markBidsOutbid` is now on the synchronous bid path, this cost is paid on every `placeBid()` call.

**Fix**: use `bidder-index` instead, which scopes the scan to only that bidder's bids across all auctions — typically a much smaller set than all bids in a popular auction:

```java
// After fix — reads only this bidder's bids, then filters by auctionId
bidStore.findByBidderId(bidderId).stream()
        .filter(b -> b.getAuctionId().equals(auctionId) && "ACTIVE".equals(b.getStatus()))
        ...
```

**Learning**: DynamoDB GSI choice directly affects query cost and latency. The access pattern here is "find bids by a specific bidder within a specific auction" — neither GSI is a perfect fit (both require a post-filter on the other dimension). At scale, the right solution would be a composite sort key like `auctionId#bidderId` to avoid any filtering. For this project, `bidder-index` is the better of the two available options because a single bidder's bid volume across all auctions is bounded, whereas a popular auction's total bid count is not.

---

## 16. Query Service Event Consumer — Write Amplification, Batch Processing, and Idempotency

### Background

The query service consumes `bid_placed` events from a Redis Stream and maintains two PostgreSQL read-model tables: `auction_summary` (one row per auction, tracks `bidCount` and `currentHighest`) and `bid_activity` (one row per (auction, bidder) pair, tracks per-bidder bid history).

The original consumer processed events one message at a time using the `handleMessage` hook in `ResilientStreamConsumer`.

These four related issues were all found during a single design review of the consumer.

---

### Issue 1: Write Amplification — 4N DB Operations per Batch

**Symptom**: For each `bid_placed` event, the consumer issued four separate database round-trips:
- `findById(auctionId)` → `save(auctionSummary)`
- `findByAuctionIdAndBidderId` → `save(bidActivity)`

With `batchSize = 20`, a fully-loaded batch produced up to 80 individual DB operations. More critically, `auction_summary` is a hot row — every bid touches the same auction's `bidCount` and `currentHighest`. Processing 20 events one-by-one means acquiring the PostgreSQL row lock 20 times sequentially, serialising what should be a bulk operation.

**Fix**: Added a `handleBatch` extension point to the `ResilientStreamConsumer` base class. The default implementation calls `handleMessage` per record (backward-compatible with all existing consumers). `BidPlacedConsumer` overrides it to process the full batch in a single `@Transactional` call:

1. One `findAllById` pre-loads all `AuctionSummary` rows touched by the batch
2. An in-batch `Map<String, BidActivity>` cache avoids duplicate DB reads for the same (auction, bidder) pair — cache misses fall back to individual DB reads and store the result (including `null`) to prevent repeated misses for the same key
3. One `saveAll(dirtySummaries)` and one `saveAll(activities)` at the end — only rows that were actually mutated are included

For a batch of 20 events, DB operations dropped from ≤80 to: 1 bulk read + ≤40 lazy activity reads + 2 bulk writes. The hot row lock is acquired once per batch instead of once per event.

---

### Issue 2: PEL Redelivery Double-Counting

**Symptom**: Not caught in testing — identified by thinking through the at-least-once delivery guarantee of Redis Streams.

**Cause**: If the consumer commits the DB write but crashes before sending the ACK, the message stays in the PEL (Pending Entry List) and is redelivered on the next consumer startup. The original handler had no idempotency check: every delivery unconditionally executed `bidCount + 1` and `bidActivity.bidCount + 1`. A single crash-and-restart cycle could inflate both counters for the same bid.

**Fix**: Added a `lastBidId` field to `AuctionSummary`, written atomically in the same transaction as `bidCount`. Before processing any event:

```java
if (event.bidId().equals(summary.getLastBidId())) return;
```

Because `lastBidId` and `bidCount` are committed together, a failed transaction leaves both fields consistent. On redelivery, the guard fires and the event is skipped cleanly.

---

### Issue 3: Batch Optimization Introduces a Partial Idempotency Gap

**Symptom**: Discovered during review of the batch implementation — the `lastBidId` guard was not sufficient for the new batch ACK model.

**Cause**: `lastBidId` only records the *last* processed event per auction in a batch. If a batch contains two events for the same auction — B1 then B2 — the committed state has `lastBidId = B2`. On batch redelivery (DB committed but ACK failed), the guard catches B2 but not B1: `B1 ≠ B2`, so B1 is processed again and `bidCount` is inflated by 1.

This is a regression introduced by the batch optimization. The per-message approach ACKed each message immediately after its own commit, so at most one message could be redelivered after a crash. The batch approach commits N events atomically and ACKs them together — if the ACK fails, all N are redelivered regardless of position in the batch.

**Fix**: Added a `Set<String> processedInBatch` local to each `handleBidPlacedBatch` invocation:

```java
if (processedInBatch.contains(event.bidId())
        || (summary != null && event.bidId().equals(summary.getLastBidId()))) {
    continue;
}
// ... after processing:
processedInBatch.add(event.bidId());
```

Two layers, two distinct failure modes:
- `lastBidId` — guards against cross-batch PEL redelivery (the common case)
- `processedInBatch` — guards against within-batch position gaps on redelivery (the regression introduced by batching)

**Key insight**: an optimisation that changes delivery atomicity boundaries can silently weaken existing correctness guarantees. The `lastBidId` guard was designed for the per-message ACK model; adopting batch ACK without auditing idempotency coverage was the gap. The correct response is to audit every idempotency assumption whenever the delivery or commit unit changes.

---

## 17. Missing Indexes in Query Service Tables

**Symptom**: Identified during table design review — no query plan analysis was needed; the absence of indexes was visible directly from the entity definitions.

**Cause**: `AuctionSummary` and `BidActivity` were defined without any `@Index` annotations. All repository query methods (`findByStatus`, `findBySellerId`, `findByBidderId`, `findByAuctionIdAndBidderId`) were issuing sequential full-table scans.

**Fix**: Added explicit `@Index` annotations to both entities. Compound indexes were chosen to match the actual access patterns — in particular the three sort options on the auction listing endpoint (`bidCount`, `currentHighest`, `updatedAt`) and the seller dashboard's combined `(sellerId, status)` filter:

```java
@Table(name = "auction_summary", indexes = {
    @Index(name = "idx_as_status",           columnList = "status"),
    @Index(name = "idx_as_seller",           columnList = "sellerId"),
    @Index(name = "idx_as_status_bidcount",  columnList = "status, bidCount"),
    @Index(name = "idx_as_status_highest",   columnList = "status, currentHighest"),
    @Index(name = "idx_as_status_updatedat", columnList = "status, updatedAt"),
    @Index(name = "idx_as_seller_status",    columnList = "sellerId, status")
})
```

`BidActivity` received a unique constraint on `(auctionId, bidderId)` — which doubles as the primary lookup index — plus single-column indexes on `bidderId` and `bidderId + bidStatus` for the buyer dashboard.

**Learning**: index design should be driven by access patterns, not added reactively after a slow query appears. For each repository query method there should be a corresponding index that supports it. In a read model like this one, where the whole point is fast reads, missing indexes are a fundamental design gap, not a tuning afterthought.

---

## 18. Query Service Schema Redesign — Replacing `bid_activity` with an Append-Only `bid` Table

### Background

After completing the batch-processing and idempotency work in Story 16, a follow-up design review of the Query Service as a whole exposed a more fundamental problem: the `bid_activity` table — one row per (auction, bidder) pair — was the wrong data model for what the service needed to do.

---

### The Core Problem: Aggregation State That Has to Be Kept Consistent

`bid_activity` stored **derived aggregate state** — `latestAmount`, `bidCount`, `firstBidAt`, `lastBidAt` — that had to be updated on every incoming `bid_placed` event. This required the consumer to:

1. Load the existing row for (auction, bidder)
2. Merge the new bid into it (max amount, increment count, update timestamps)
3. Mark the previous bidder as `OUTBID`, which required a second lookup and write

The end result was a consumer with ~120 lines of logic, an in-batch activity cache, dirty-set tracking, and two layers of idempotency protection. All of this complexity existed not to do something useful, but to maintain counts and maximums that could be computed from the raw bid log.

**The fundamental design error**: `bid_activity` was an aggregation layer maintained by the consumer instead of a pure event log. The data it stored (who bid, when, how much) was being mutated rather than appended — losing history in the process. If a buyer places three bids, only the latest `latestAmount` and the incremented `bidCount` are visible; the individual bid amounts are gone.

---

### Diagnosis: Why Was This Design Chosen?

The `bid_activity` design came from treating the read model as a denormalisation of the write side. The original intuition was: "for each bidder on each auction, keep a summary row so queries are fast". This is a reasonable starting point, but it conflated two separate concerns:

1. **What happened** (append-only event log — the source of truth)
2. **How to answer queries fast** (derived aggregation — can be computed at read time or cached)

By storing the aggregation in the table and keeping it consistent through event processing, the consumer had to solve a consistency problem that the database could solve trivially via `GROUP BY`.

---

### The Redesign

Replaced `auction_summary` + `bid_activity` with three normalised tables:

| Table | Responsibility | Idempotency mechanism |
|---|---|---|
| `auction` | Structural fields + stored `bidCount`/`currentHighest` | Upsert by `auctionId` PK |
| `bid` | Append-only record per accepted bid | `bidId` PK — `existsById` check |
| `auction_winner` | One row per winner, written at auction close | UNIQUE(`auctionId`, `bidderId`) constraint |

**`bid` is the key change.** Each event appends one row with the full bid data (`bidId`, `bidderId`, `amount`, `bidAt`). No merging. No aggregation. The `bidId` from `BidPlacedEvent` is the natural idempotency key: if the row already exists, the event is a PEL replay — skip it.

The per-bidder aggregates (`latestAmount`, `bidCount`, `firstBidAt`, `lastBidAt`) that `bid_activity` stored are now computed in the database using `GROUP BY`:

```sql
SELECT bidder_id, MAX(amount) AS latest_amount, COUNT(*) AS bid_count,
       MIN(bid_at) AS first_bid_at, MAX(bid_at) AS last_bid_at
  FROM bid
 WHERE auction_id = :auctionId
 GROUP BY bidder_id
```

`bidCount` and `currentHighest` are still stored on the `auction` row as a query-time optimisation — the public listing endpoint sorts by them, and a `GROUP BY` on every page request would be expensive. These values are updated by a single native SQL `UPDATE ... SET bid_count = bid_count + 1, current_highest = GREATEST(current_highest, :amount)` that runs after the bid insert.

---

### `BidPlacedConsumer` Before and After

**Before** (~120 lines, multiple data structures, two idempotency layers):
```java
// Batch pre-load, in-batch cache, dirty tracking, previous-bidder OUTBID update,
// lastBidId + processedInBatch idempotency, saveAll(dirtySummaries), saveAll(activities)
```

**After** (~40 lines, one idempotency check):
```java
@Transactional
public void handleBidPlaced(BidPlacedEvent event) {
    if (bidRepo.existsById(event.bidId())) return;   // idempotency

    Bid bid = new Bid(event.bidId(), event.auctionId(), event.userId(),
                      event.itemId(), event.amount(), Instant.parse(event.bidAcceptedAt()));
    bidRepo.save(bid);

    int updated = auctionRepo.incrementBidCounters(event.auctionId(), event.amount(), Instant.now());
    if (updated == 0) {
        // bid arrived before auction:created — create skeletal auction row
        auctionRepo.save(skeletalAuction(event));
    }
}
```

The entire batch-processing machinery, the activity cache, the dirty-set, the `lastBidId` cross-batch guard, and the previous-bidder `OUTBID` tracking were all deleted. The previous-bidder status is now computed at query time from `auction_winner` — a bid is `WON` if there's an `auction_winner` row for (auction, bidder), `OUTBID` if the auction is closed and no winner row exists, `ACTIVE` otherwise. No consumer needs to maintain this.

---

### `AuctionClosedConsumer` Simplification

The old consumer did three writes: update `auction_summary` (mark CLOSED), then load all `bid_activity` rows for the auction, then mark each one WON or OUTBID. That was a full table scan and a bulk update whose only purpose was to populate a status that can be derived.

The new consumer does two writes: mark `auction` as CLOSED, then insert one `auction_winner` row per winner. The `OUTBID` state for non-winners is a derived fact, not stored state.

---

### Query-Time Status Derivation in Native SQL

The buyer dashboard and seller bids view use a `BidSummaryProjection` — a Spring Data interface projection backed by a native SQL query that joins `bid`, `auction`, and `auction_winner` in a single round-trip:

```sql
CASE WHEN aw.bidder_id IS NOT NULL THEN 'WON'
     WHEN a.status = 'CLOSED'      THEN 'OUTBID'
     ELSE 'ACTIVE' END AS bidStatus
```

This computes the status in the database without any consumer needing to maintain it. The buyer's `?status=WON` filter is applied as a `HAVING` clause on the same query — the status filter and pagination both happen in one SQL round-trip with `LIMIT / OFFSET`.

---

### Flyway Migration

`V3__redesign_query_tables.sql` drops `bid_activity` and `auction_summary`, then creates `auction`, `bid`, and `auction_winner` with appropriate indexes. The old entities and repository interfaces were deleted from the codebase.

---

### Key Insights

**Derived state should be computed, not maintained.** Every piece of state that can be expressed as a query over an append-only log is a candidate for deletion from the write path. `latestAmount`, `bidCount`, `bidStatus` — all of these are queries over the raw bid records, not facts that need to be stored separately.

**Idempotency becomes trivial with an append-only model.** The two-layer `lastBidId` + `processedInBatch` mechanism in Story 16 existed because the consumer was updating mutable aggregation state. Once the model is append-only with a natural PK, idempotency is a single `existsById` check. The complexity of the old guard was a symptom of the wrong data model.

**The read model is a view, not a materialised copy of the write side.** Designing the read model as "the write model but denormalised" leads to the same consistency problems as keeping two databases in sync. A better mental model is: the `bid` table is an event log, and the query layer computes any view it needs from that log at read time. Stored aggregations are an optimisation (for sort/pagination), not the foundation.
