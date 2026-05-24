# Load Test Plan

> **Note**: This plan was written when `PessimisticStrategy` still used a Redisson distributed lock.
> The lock has since been removed (see `docs/troubleshooting.md` #14). References to "lock contention"
> below describe the pre-optimization state. The test scenarios and consistency checks remain valid.
> bid-service has since been merged into auction-service; references to bid-service below reflect the pre-consolidation state.

## Goals

1. **Validate claim**: "sub-50ms bid acknowledgement under concurrent load"
2. **Identify bottleneck**: is it Redis, DynamoDB, Lua script queuing, or application code?
3. **Produce citable numbers**: p50 / p95 / p99 latency, throughput (bids/sec), error rate
4. **Verify consistency**: Redis and DynamoDB stay in sync under concurrent writes
5. **Profile resources**: CPU and memory per container to pinpoint where capacity is spent

## Test Scenarios

### Test 1 — Single Auction, Ramp-Up Concurrency

The hot-path stress test. Multiple buyers bidding on the **same** auction simultaneously.
This is the hardest case: all bids compete for the same Redisson lock.

| Parameter         | Value             |
|-------------------|-------------------|
| Target endpoint   | `PUT /auctions/{id}/bid` |
| Auction setup     | 1 auction, quantity=1, endTime=+5min |
| Bidders           | 50 pre-registered buyers |
| Concurrency       | 10 → 25 → 50 concurrent threads |
| Duration          | 15s per concurrency level |
| Bid amounts       | Monotonically increasing (random increment) |
| Measured          | p50/p95/p99 latency, throughput, error rate, peak CPU/mem |

**What this tells us**: Redisson lock contention impact. Since all bids hit the same auction,
the lock serialises them. We expect throughput to plateau and latency to climb with concurrency.

**Consistency checks after each level**:
- Redis `bid_count` == DynamoDB `bidCount`
- Redis `current_highest` == DynamoDB `currentHighest`
- Winners ZSET size <= quantity

### Test 2 — Multiple Auctions, Parallel Bidding

Simulates realistic production load where bids spread across different auctions.

| Parameter         | Value             |
|-------------------|-------------------|
| Target endpoint   | `PUT /auctions/{id}/bid` |
| Auction setup     | 20 auctions, quantity=3 each |
| Bidders           | 100 pre-registered buyers |
| Concurrency       | 50 concurrent threads |
| Duration          | 30s |
| Bid distribution  | Random across auctions |
| Measured          | p50/p95/p99 latency, throughput, error rate, peak CPU/mem |

**What this tells us**: Real-world throughput. Locks are per-auction, so 20 auctions
means 20x the parallelism of Test 1. This is the number for the resume.

**Consistency checks after test**:
- All 20 auctions: Redis state == DynamoDB state
- DynamoDB winners map members == Redis ZSET members
- bid history records exist for each auction with bids (GET /auctions/{id}/bids)

### Test 3 — Auction Lifecycle Under Load

End-to-end test: create auction → bid → auction closes → payment created.
Validates that the event pipeline (Redis Streams → payment-service, query-service) keeps up.

| Parameter         | Value             |
|-------------------|-------------------|
| Auctions          | 10 auctions, endTime=+30s |
| Bidders           | 30 buyers |
| Concurrency       | 20 concurrent threads |
| After close       | Verify payments, bid statuses, Redis cleanup |
| Measured          | Bid latency + full post-close verification |

**What this tells us**: Whether downstream consumers (bid-service, payment-service)
are bottlenecks and whether the entire system reaches a consistent state after close.

**Post-close consistency checks**:
- All auctions status == CLOSED
- Redis hash and ZSET keys deleted (cleanup)
- Payment exists for each auction with bids
- Bid history records exist; winners identified via Auction.winners (no WON status in Bids table)
- Redis Stream consumer lag == 0

## Metrics Captured

### Performance

| Metric | Source | How |
|--------|--------|-----|
| Bid HTTP latency (p50/p95/p99) | Load test script | Per-request `curl` timing |
| Bid duration (Lua execution) | Micrometer | `GET /actuator/metrics/auction.bid.duration` |

### Resources

| Metric | Source | How |
|--------|--------|-----|
| CPU % per container | `docker stats` | Sampled every 2s during test |
| Memory % per container | `docker stats` | Sampled every 2s during test |
| Peak CPU / Peak Memory | Post-test report | Max across all samples |

### Consistency

| Check | What it verifies |
|-------|-----------------|
| Redis bidCount == DynamoDB bidCount | Lua script atomicity |
| Redis currentHighest == DynamoDB currentHighest | Async DynamoDB persistence consistency |
| Redis ZSET members == DynamoDB winners map | Winners snapshot consistency |
| Winners ZSET size <= quantity | Multi-winner correctness |
| Bid history records exist | Synchronous write in placeBid() |
| Winners recorded in Auction.winners | Close flow persistence |
| Payments created after close | Event delivery (auction:closed → payment-service) |
| Redis keys cleaned after close | Close flow cleanup |
| Stream consumer lag == 0 | All events fully consumed |

## Tool

The load test script (`run.sh`) uses plain **bash + curl** with background jobs.
No external dependencies needed — it runs on any machine with `curl`, `bash`, and `python3`.

Why not wrk/k6/gatling:
- Our bid endpoint requires auth tokens (JWT) and monotonically increasing amounts
- Each request depends on state (previous bid amount) — not a static payload
- curl gives us precise per-request timing and error capture
- Simple to read and modify for interview discussion

## Setup & Run

```bash
# 1. Start all services
docker-compose up --build -d

# 2. Wait for healthy
bash scripts/smoke-test.sh

# 3. Run all tests
bash loadtest/run.sh

# Or run individually
bash loadtest/run.sh test1   # lock contention
bash loadtest/run.sh test2   # throughput
bash loadtest/run.sh test3   # lifecycle + consistency
```

Results are saved to `loadtest/results/`.

## Expected Results (Hypothesis)

| Scenario | Expected p50 | Expected p95 | Bottleneck |
|----------|-------------|-------------|------------|
| Test 1 (single auction, 50 concurrent) | 30-60ms | 80-150ms | Redisson lock wait |
| Test 2 (20 auctions, 50 concurrent) | 10-30ms | 30-60ms | Redis round-trips |
| Test 3 (lifecycle) | 15-40ms (bid) | Event propagation: <2s | Consumer throughput |

If Test 2 p95 < 50ms, the resume claim holds. If not, we know exactly where to optimize.

**Expected consistency**: Zero failures across all checks. Any failure indicates a concurrency bug.

**Expected resource profile**: auction-service should have the highest CPU (lock + Lua + DynamoDB writes).
Redis should stay under 30% CPU — if higher, Redis is the bottleneck.
