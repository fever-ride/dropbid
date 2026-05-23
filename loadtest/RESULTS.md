# Load Test Results

> **Note**: These results were captured with the Redisson distributed lock still in place.
> The lock has since been removed (see `docs/troubleshooting.md` #14). Post-optimization
> results are expected to show significantly lower latency for Test 1 (single-auction hot path).

Test environment: Docker Desktop (macOS, Apple Silicon), single-machine deployment, all services sharing CPU/memory.

Date: 2026-04-25

## Test 1: Single Auction Lock Contention

50 buyers bidding on the same auction. Tests Redisson distributed lock behavior at increasing concurrency.

| Concurrency | Total Requests | Success | p50 | p95 | p99 | max |
|-------------|---------------|---------|-----|-----|-----|-----|
| 10 | 1422 | 127 | 81ms | 135ms | 310ms | 323ms |
| 25 | 1569 | 64 | 187ms | 424ms | 496ms | 496ms |
| 50 | 1585 | 21 | 272ms | 534ms | 573ms | 573ms |

**Consistency**: 3 rounds x 5 checks = 15/15 PASS. Redis and DynamoDB fully consistent.

**Analysis**:

Latency scales linearly with concurrency because all requests compete for a single lock. p50 grows from 81ms to 272ms, confirming that lock queuing is the dominant latency factor.

Successful bids drop (127 -> 21) because the test window is fixed at 15s. Lock hold time stays constant, so higher concurrency means longer queuing and fewer complete lock-bid-unlock cycles per unit time.

The high HTTP 403 rate is expected. Under pessimistic locking, requests that fail to acquire the lock are rejected immediately. Clients are expected to retry. This is not an error; it's the concurrency control working as designed.

## Test 2: Multi-Auction Throughput

20 auctions (quantity=3 each), 100 buyers, 50 concurrent, 30 seconds.

| Metric | Value |
|--------|-------|
| Total requests | 2961 |
| Successful bids | 399 |
| Success rate | 13.5% |
| p50 | 326ms |
| p95 | 566ms |
| p99 | 689ms |
| max | 786ms |
| Throughput | ~99 req/s (successful: ~13.3 bids/s) |

**Consistency**: 20 auctions x 5 checks = 100/100 PASS.

**Analysis**:

With 50 concurrent threads spread across 20 auctions, each auction averages 2-3 concurrent bidders. Lock contention per auction is much lower than Test 1.

399 successful bids distributed evenly across 20 auctions (range: 11-26 per auction), confirming good load distribution.

## Test 3: Full Lifecycle

10 auctions, 30 buyers, 20 concurrent for 15s, then wait for auction close + payment processing.

### Bid Phase

| Metric | Value |
|--------|-------|
| Total requests | 1455 |
| Successful bids | 104 |
| p50 | 181ms |
| p95 | 294ms |
| p99 | 377ms |

### Post-Close Verification

| Check | Result |
|-------|--------|
| 10/10 auctions status = CLOSED | PASS |
| 10/10 Redis hash/ZSET keys cleaned up | PASS |
| 10/10 no ACTIVE bids remain (all transitioned to WON/OUTBID) | PASS |
| bid_placed consumer lag | 0 pending |
| auction:closed consumer lag | 0 pending |

30/30 checks passed.

**Analysis**:

The event pipeline (Redis Streams -> bid-service, payment-service) keeps up under load. All bid statuses transition correctly and Redis keys are fully cleaned up after auction close. Zero consumer lag confirms consumption rate matches production rate.

## Resource Usage

Peak CPU/Memory per container (Test 2, the most representative):

| Container | Peak CPU | Peak Mem |
|-----------|----------|----------|
| auction-service | 291.9% | 5.7% |
| bid-service | 75.1% | 4.0% |
| DynamoDB Local | 73.1% | 3.9% |
| Redis | 32.0% | 0.1% |
| payment-service | 14.6% | 5.2% |
| query-service | 8.4% | 5.0% |
| notification-service | 6.7% | 3.7% |

The bottleneck is auction-service (lock management + Lua script + DynamoDB writes). Redis CPU stays below 30% and is not the bottleneck. DynamoDB Local peaked at 114.5% during Test 3 due to batch reads/writes at auction close time.

## Comparison with Expectations

| Scenario | Expected p50 | Actual p50 | Why the difference |
|----------|-------------|------------|-------------------|
| Test 1 (c=50) | 30-60ms | 272ms | Estimate was too optimistic. DynamoDB Local (single-machine Java process) is much slower than AWS managed. curl process overhead adds latency too |
| Test 2 (50 concurrent) | 10-30ms | 326ms | Same as above, plus bash/curl test tooling overhead |
| Test 3 (bids) | 15-40ms | 181ms | 20 concurrency is lower than Test 1/2's 50, so less lock contention |

Actual latency exceeds estimates mainly because:
1. DynamoDB Local is a single-machine Java process with write latency far above AWS managed DynamoDB
2. The test tool spawns a new curl process per request, adding process creation overhead
3. Docker Desktop shares CPU across all services, creating resource contention

On AWS (managed DynamoDB + ECS/EKS with dedicated resources), p50 is expected to drop to the 20-50ms range.

## Key Takeaways

1. **100% data consistency**: Redis and DynamoDB stayed in sync across all tests. Zero data loss.
2. **Pessimistic locking works**: Correctly serialized all bids under high concurrency. No race conditions detected.
3. **Event pipeline is reliable**: All bid statuses transitioned correctly. Consumer lag reached zero.
4. **Clear bottleneck**: auction-service has the highest CPU. The optimization path is to move the DynamoDB write outside the lock hold window (async persistence).
5. **Horizontal scaling is viable**: Locks are per-auction, so multi-auction workloads parallelize naturally. Adding auction-service instances increases throughput proportionally.
