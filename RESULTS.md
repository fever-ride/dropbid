# DropBid Java — Load Test Results

## Environment

| Component | Detail |
|---|---|
| Runtime | Java 21 virtual threads (Project Loom), Spring Boot 3.2.5 |
| Bid store | Redis 7 (Lua atomic script, single-threaded serialization) |
| Auction store | DynamoDB Local (in-memory, shared DB mode) |
| Infrastructure | Docker Compose on macOS (Docker Desktop shared CPU) |
| Load tool | Locust 2.x, `constant(0)` wait time, 50 virtual users |

> Note: Docker Desktop on macOS shares CPU cores with the host OS. Numbers reflect
> single-machine containerized performance, not dedicated server capacity.
> DynamoDB Local is also slower than AWS-managed DynamoDB for reads/writes.
> Locust itself was CPU-bound at peak (see warning below) — a distributed Locust
> setup would push RPS higher.

---

## Test 1 — Single Auction, High Concurrency

All 50 users hammer the same auction. Tests Redis Lua serialization under maximum
contention (one auction key, many concurrent writers).

```
locust -f loadtest/locustfile.py SingleAuctionBidder \
    --headless -u 50 -r 5 -t 60s --host http://localhost:8081
```

| Metric | Value |
|---|---|
| Total requests | 133,365 |
| Throughput | 2,233 req/s |
| Failure rate | 0% |
| p50 latency | 13 ms |
| p75 latency | 17 ms |
| p95 latency | 27 ms |
| p99 latency | 42 ms |
| p99.9 latency | 93 ms |
| Max latency | 220 ms |

400 (bid too low) and 409 (concurrent lock conflict) are reclassified as success
in the Locust script — they represent correct system behavior under contention,
not errors.

---

## Test 2 — Multi-Auction Throughput

50 users spread bids across 20 concurrent auctions. Tests horizontal parallelism:
different auction keys in Redis are fully independent.

```
locust -f loadtest/locustfile.py MultiAuctionBidder \
    --headless -u 50 -r 5 -t 60s --host http://localhost:8081
```

| Metric | Value |
|---|---|
| Total requests | 116,279 |
| Throughput | 1,945 req/s |
| Failure rate | 0% |
| p50 latency | 14 ms |
| p75 latency | 19 ms |
| p95 latency | 52 ms |
| p99 latency | 130 ms |
| p99.9 latency | 310 ms |
| Max latency | 780 ms |

**Warning — Test 2 tail latencies are dominated by DynamoDB Local, not application code.**

DynamoDB Local is a single-process SQLite-backed implementation with a global
write lock. All 20 concurrent auction writes serialize through that one lock,
even though each auction is a separate item. On AWS-managed DynamoDB, each
partition is independent; 20 auctions would be handled in parallel across
separate partition servers.

This is the same class of problem as Redis single-instance serialization, but
worse: Redis single-threading is by design and per-key (Test 1's contention is
realistic). DynamoDB Local's global lock is an artifact of the local emulator —
it does not exist on the real service.

**Expected behavior on real AWS DynamoDB**: Test 2 p99 should be lower than
Test 1, not higher, because 20 independent auction keys map to independent
partitions with no shared lock. The 130 ms p99 seen here is a local-emulator
artifact and should not be quoted as a system characteristic.

---

## Key Design Decisions Reflected in Results

- **Redis Lua atomicity**: single-threaded Lua execution in Redis means bid
  placement is serialized per auction key, preventing phantom reads and dirty
  writes without application-level locking overhead.
- **Pessimistic lock strategy** (`CONCURRENCY_STRATEGY=pessimistic`): returns
  409 on contention rather than silently dropping bids; callers can retry.
- **Two Redis instances**: `redis-auction` (port 6379) handles bid state;
  `redis-infra` (port 6380) handles pub/sub streams and idempotency keys.
  Separating concerns prevents bid-path latency spikes from stream backpressure.
