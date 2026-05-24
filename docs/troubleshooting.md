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
