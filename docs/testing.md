# Testing Strategy

## Overview

The project uses four layers of testing.

| Layer | What it covers | Status |
|-------|---------------|--------|
| Unit | Business logic in isolation (no real DB/Redis) | **Active** — shared, user-service, shop-service, payment-service, auction-service, query-service |
| Integration | DB queries, stream consumers against real infra | Not yet written |
| End-to-end (smoke) | Full request flow across services | `scripts/smoke-test.sh` |
| Performance / Load | Latency, throughput, consistency under concurrent load | `loadtest/run.sh` |

---

## Unit Tests

### How to Run

```bash
# Individual module (run from the repo root)
cd shared           && mvn test
cd user-service     && mvn test
cd shop-service     && mvn test
cd payment-service  && mvn test
cd auction-service  && mvn test
cd query-service    && mvn test

# All modules at once (from repo root — skips bid-service which has no src)
mvn test --projects shared,user-service,shop-service,payment-service,auction-service,query-service
```

JaCoCo HTML reports are generated at `target/site/jacoco/index.html` after each `mvn test` run.

---

### Current Coverage (after JaCoCo exclusions)

#### shared — (no overall % — library module, not an application)

| Package | Instr | Branch | Notes |
|---------|-------|--------|-------|
| `security` (JwtUtil + JwtAuthFilter) | ~100% | 100% | 12 tests; UserPrincipal is a record |

#### payment-service — ~75% instructions, ~65% branches

| Package | Instr | Branch | Notes |
|---------|-------|--------|-------|
| `service` (PaymentService + RecoveryJob) | 90% | 88% | 23 tests; core state machine fully covered |
| `controller` | ~90% | ~75% | 9 WebMvcTest tests; all endpoints + 401 paths covered |
| `model` | 74% | — | JPA lifecycle callbacks (`@PrePersist`/`@PreUpdate`) not exercised without container |
| `events` | 1% | — | `AuctionClosedConsumer` + `PaymentEventPublisher` not yet tested |
| `config` | 0% | — | SecurityConfig, exercised via `@Import` in controller slice but not counted separately |
| `repository` | *excluded* | — | JPA repository, needs integration tests |

#### user-service — ~80% instructions, ~75% branches

| Package | Instr | Branch | Notes |
|---------|-------|--------|-------|
| `service` | 100% | 100% | 11 tests — register/login/getById all paths |
| `controller` | ~95% | ~80% | 12 WebMvcTest tests; public endpoints + auth-required + 401/400 paths covered |
| `model` | 68% | — | JPA `@PrePersist` not exercised without container |
| `dto` | 52% | — | Record constructors; exercised via controller serialization |
| `events` | 9% | — | `UserEventPublisher` not yet tested |
| `config` | 0% | — | SecurityConfig, exercised via `@Import` in controller slice |
| `repository` | *excluded* | — | JPA repository, needs integration tests |

#### shop-service — ~80% instructions, ~70% branches

| Package | Instr | Branch | Notes |
|---------|-------|--------|-------|
| `service` | 100% | 100% | 14 tests — createShop/addItem/getItem/listItems all paths |
| `controller` | ~95% | ~80% | 18 WebMvcTest tests; SELLER-only endpoints + BUYER 403 + unauthenticated 401 + Bean Validation 400 |
| `model` | 67% | — | JPA `@PrePersist` not exercised without container |
| `events` | 9% | — | `ItemEventPublisher` not yet tested |
| `config` | 0% | — | SecurityConfig, exercised via `@Import` in controller slice |
| `repository` | *excluded* | — | JPA repository, needs integration tests |

#### auction-service — 88% instructions, 72% branches

| Package | Instr | Branch | Notes |
|---------|-------|--------|-------|
| `bid.controller` | 100% | 72% | 7 WebMvcTest tests |
| `controller` | 98% | 50% | 11 WebMvcTest tests |
| `model`, `bid.model`, `dto` | 100% | — | Pure data classes |
| `scheduler` | 89% | 100% | Opener + Closer, 4 tests each |
| `events` | 88% | — | EventPublisher, 3 tests |
| `service` | 86% | 73% | 40 service-layer tests |
| `concurrency` (BidManager + PessimisticStrategy) | 94% | 80% | 9 PessimisticStrategy tests added |
| `repository` / `bid.repository` | *excluded* | — | DynamoDB layer, needs integration tests |
| `concurrency/experimental` | *excluded* | — | Prototype strategies, not production |

#### query-service — 73% instructions, 46% branches

| Package | Instr | Branch | Notes |
|---------|-------|--------|-------|
| `events` | 85% | 94% | All 5 consumers tested |
| `model` | 93% | — | |
| `dto` | 100% | 100% | EnrichedAuctionSummary + EnrichedBidActivity null-guard paths exercised |
| `controller` | 70% | 40% | QueryControllerTest (12) + EnrichmentServiceTest (12); HTTP fallback not covered (no WireMock) |
| `config` | 19% | — | SecurityConfig covered by controller slice; ColdStartSync not testable without WireMock |

---

### JaCoCo Exclusion Strategy

Three categories of code are excluded from coverage measurement. The exclusions are
configured once in the **parent `pom.xml`** under the `jacoco-maven-plugin` `<configuration>`
block so that they apply to every module automatically.

```xml
<excludes>
    <!-- Prototype concurrency strategies — not production-ready, no unit tests intentional -->
    <exclude>**/concurrency/experimental/**</exclude>

    <!-- Infrastructure / persistence adapters (DynamoDB, Redis stores).
         These require a running data store; they belong in integration tests. -->
    <exclude>**/repository/**</exclude>

    <!-- Spring Boot main entry points — nothing to unit-test. -->
    <exclude>**/*Application.class</exclude>
</excludes>
```

**Why this matters**: without these exclusions, the auction-service overall number reads 52%
because the DynamoDB store classes (0% coverage, cannot be unit-tested) drag down the total.
After exclusion the reported number (88%) reflects only the code that CAN be meaningfully
unit-tested, giving an honest signal of test gaps.

---

### Test Inventory

**Total: 237 tests across 6 modules** (as of 2026-06-01)

| Module | Tests | Service pkg | Overall |
|--------|-------|-------------|---------|
| `shared` | 12 | ~100% | security package |
| `user-service` | 23 | 100% / 100% | ~80% instr (controller slice added) |
| `shop-service` | 32 | 100% / 100% | ~80% instr (controller slice added) |
| `payment-service` | 32 | 90% / 88% | ~75% instr (controller slice added) |
| `auction-service` | 89 | 86% / 73% | 88% instr / 72% branch |
| `query-service` | 49 | — | 73% instr / 46% branch |

---

#### user-service (23 tests total)

| Test Class | Tests | What it covers |
|------------|-------|---------------|
| `UserServiceTest` | 11 | `register`: new email, null role → BUYER, explicit SELLER, password hashed (BCrypt), event published, duplicate email → 409; `login`: correct credentials, unknown email → 401, wrong password → 401; `getById`: found, not found → 404 |
| `UserControllerTest` | 12 | `POST /users/register`: 201 + AuthResponse, no-auth (permitAll), missing email → 400, invalid role → 400; `POST /users/login`: 200 + token, no-auth (permitAll), missing password → 400; `GET /users/{id}`: 200, 404, unauthenticated → 401; `GET /users/me`: 200 using principal.userId(), unauthenticated → 401 |

#### shop-service (32 tests total)

| Test Class | Tests | What it covers |
|------------|-------|---------------|
| `ShopServiceTest` | 14 | `createShop`: success (name/bio saved), duplicate owner → 409; `getShop`: found, not found → 404; `getShopByOwner`: found, not found → 404; `addItem`: owner match (item fields + event published), shop not found → 404, not owner → 403; `listItems`: multiple items, empty list; `getItem`: found, not found → 404 |
| `ShopControllerTest` | 18 | `POST /shops`: 201 as SELLER, 403 as BUYER, 401 unauthenticated, 400 missing name; `GET /shops/{id}`: 200, 404, 401; `GET /shops/owner/{ownerId}`: 200, 401; `POST /shops/{shopId}/items`: 201 as SELLER, 403 as BUYER, 401 unauthenticated, 400 invalid condition, 400 missing title; `GET /shops/{shopId}/items`: 200, 401; `GET /items/{id}`: 200, 401 |

#### shared (12 tests total)

| Test Class | Tests | What it covers |
|------------|-------|---------------|
| `JwtUtilTest` | 7 | `generateToken`+`validateToken` round-trip; short-secret padding; different-instance cross-validate; tampered / wrong-secret / random-string / empty-string rejection |
| `JwtAuthFilterTest` | 5 | No header → pass-through; non-Bearer header → pass-through; valid token → SecurityContext populated with UserPrincipal + correct role authority; invalid token → 401, filter chain not called |

#### payment-service (32 tests total)

| Test Class | Tests | What it covers |
|------------|-------|---------------|
| `PaymentServiceTest` | 17 | `initiatePayments`: null/empty winners, new winner, idempotent existing-payment, multiple winners; `processPayment`: 404, terminal state early-return (COMPLETED/FAILED), gateway success→COMPLETED+publish, gateway failure→FAILED+publish, stored decision reuse (no double-charge); `abandonPayment`: sets FAILED + "max retries exceeded" reason + publishes; `incrementRetryCount`: increments + resets to PENDING; `getByAuctionId`: empty→404, found→list |
| `RecoveryJobTest` | 6 | No stuck payments → nothing called; below maxRetries → increment+process; at maxRetries → abandon; exceeds maxRetries → abandon; one throws → loop continues for others; mixed retry counts → correct branch per payment |
| `PaymentControllerTest` | 9 | `GET /payments/{id}`: 200 + JSON, 404, 401; `GET /payments/auction/{auctionId}`: 200 list, 401; `GET /payments/user/{userId}`: 200 list, 401; `GET /payments/me`: 200 using principal.userId() (verified via `verify(service)`), 401 |

#### auction-service (89 tests total)

| Test Class | Tests | What it covers |
|------------|-------|---------------|
| `AuctionServiceTest` | 40 | Core bid logic, winner eviction, maxPrice, floor recalculation, `openAuction` / `closeAuction` state transitions, `pollDueAuctionIds`, `rebuildSchedules`, async bid-history write (Awaitility) |
| `AuctionControllerTest` | 11 | REST layer: GET/POST/PUT endpoints, role-based access (SELLER/BUYER), Bean Validation (400), unauthenticated (401), forbidden (403) |
| `BidControllerTest` | 7 | Bid history endpoints, live Redis ZSET vs DynamoDB fallback, ADMIN-only route enforcement |
| `PessimisticStrategyTest` | 9 | `tryPlaceBid`: success (no winners), success with winner pairs, AUCTION_CLOSED, BID_TOO_LOW, PRICE_TOO_HIGH, unknown Redis error, null result, short result, `name()` |
| `AuctionOpenerTest` | 4 | Scheduler tick: opens each due auction, empty poll, Redis failure, open failure |
| `AuctionCloserTest` | 4 | Scheduler tick: closes each due auction, empty poll, Redis failure, close failure |
| `AuctionEventPublisherTest` | 3 | Redis Stream key and JSON payload for `bid_placed`, `auction:created`, `auction:closed` events |

#### query-service (49 tests total)

| Test Class | Tests | What it covers |
|------------|-------|---------------|
| `AuctionCreatedConsumerTest` | 6 | Row creation, field mapping, status guard (CLOSED not overwritten), idempotency on redeliver |
| `BidPlacedConsumerTest` | 4 | Bid row insert, `bidCount` / `currentHighest` update, duplicate handling |
| `AuctionClosedConsumerTest` | 5 | Status update, winner rows, idempotency, DLQ scenario |
| `PaymentEventConsumerTest` | 4 | `payment:processed` and `payment:failed` status updates |
| `ItemUpdatedConsumerTest` | 3 | Create and update `ItemLookup`, malformed JSON |
| `UserUpdatedConsumerTest` | 3 | Create and update `UserLookup`, malformed JSON |
| `QueryControllerTest` | 12 | BuyerQueryController, SellerQueryController, PublicQueryController endpoints; role enforcement (BUYER/SELLER/ADMIN/unauth 401/forbidden 403); EnrichmentService mocked |
| `EnrichmentServiceTest` | 12 | `enrichAuctions`: item found / missing / empty page / multiple; `enrichBids`: all found / user missing / item missing / empty; `enrichBidList`: all found / empty / multiple / partial cache-hit (missing lookup silently null) |

---

### Key Implementation Notes for Controller Tests

#### Why `@Import(SecurityConfig.class)` is required

`@WebMvcTest` uses a type-filter that only loads beans in the *web layer* (`@Controller`,
`Filter`, `WebMvcConfigurer`, etc.).  Our `SecurityConfig` is a plain `@Configuration` class
and is **silently excluded** by the filter.  Without the import, Spring Boot falls back to its
default security (session-based, CSRF enabled), which breaks all role-based and JWT tests.

```java
@WebMvcTest(AuctionController.class)
@Import(SecurityConfig.class)   // ← required
class AuctionControllerTest { ... }
```

#### Why we do not use `SecurityMockMvcRequestPostProcessors.authentication()`

With `SessionCreationPolicy.STATELESS`, the `SecurityContextRepository` in the filter chain
is `NullSecurityContextRepository`.  The `authentication()` post-processor saves the security
context to this repository — which is a no-op — so the context is always empty when the
filter chain executes.

Instead, tests drive authentication through the **real `JwtAuthFilter`** by mocking
`JwtUtil.validateToken()` and attaching an `Authorization: Bearer <token>` header to each
request.  This tests the full production security pipeline.

```java
@MockBean JwtUtil jwtUtil;

@BeforeEach
void setUp() {
    Claims sellerClaims = mock(Claims.class);
    when(sellerClaims.getSubject()).thenReturn("seller-1");
    when(sellerClaims.get("role", String.class)).thenReturn("SELLER");
    when(jwtUtil.validateToken("seller-token")).thenReturn(sellerClaims);
    // ... buyer, admin tokens
}

@Test
void createAuction_validRequest_asSeller_returns201() throws Exception {
    mockMvc.perform(post("/auctions")
                    .header("Authorization", "Bearer seller-token")
                    ...)
            .andExpect(status().isCreated());
}
```

---

### Bugs Found During Test Writing

#### 1. `SecurityConfig` returning 403 instead of 401 for unauthenticated requests

**Discovery**: the unauthenticated controller tests expected HTTP 401 but received 403.

**Cause**: Spring Security 6.2 with `SessionCreationPolicy.STATELESS` and no `formLogin()` /
`httpBasic()` defaults to `Http403ForbiddenEntryPoint`.  For a JWT REST API the semantically
correct response when credentials are absent is **401 Unauthorized** (not 403 Forbidden, which
means credentials were provided but access was denied).

**Fix** (applied to all services):

```java
.exceptionHandling(ex -> ex
    .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
)
```

✅ Fix confirmed in: `auction-service`, `query-service`, `user-service`, `shop-service`,
`payment-service`, `notification-service`.

---

#### 2. `AuctionClosedConsumer.init()` access modifier narrowing

**Discovery**: `payment-service` failed to compile with `init() cannot override init() in
ResilientStreamConsumer — attempting to assign weaker access privileges; was public`.

**Cause**: `ResilientStreamConsumer.init()` is declared `public`, but
`AuctionClosedConsumer` overrode it as `protected`, which Java forbids.

**Fix**: changed `protected void init()` → `public void init()` in
`payment-service/events/AuctionClosedConsumer.java`.

---

#### 3. `UnnecessaryStubbing` with Mockito strict-stubs mode in shared `@BeforeEach`

**Discovery**: `UserServiceTest` tests for 401/404/409 error paths failed with
`UnnecessaryStubbing` because `jwtUtil.generateToken(any(), any())` was stubbed in
`@BeforeEach` but never invoked in tests that throw before reaching the token-generation step.

**Cause**: Mockito's default `@ExtendWith(MockitoExtension.class)` uses `STRICT_STUBS`
mode, which fails tests that set up stubs that go unused.

**Fix**: use `lenient().when(...)` for shared stubs that are not reached by every test:

```java
@BeforeEach
void setUp() {
    lenient().when(jwtUtil.generateToken(any(), any())).thenReturn("mock-jwt");
}
```

---

## Integration Tests

### `BidRepository` native SQL queries

The buyer dashboard and seller bids view use GROUP BY native queries with computed `bidStatus`.
These queries need to be tested against a real PostgreSQL instance (e.g. Testcontainers):

- `findBidSummariesByBidderId`: correct aggregation, ordering by `lastBidAt`
- `findBidSummariesByBidderIdAndStatus`: HAVING filter returns only WON / OUTBID / ACTIVE rows correctly
- `findBidSummariesByAuctionId`: per-bidder aggregation, sorted by `latestAmount`
- Status derivation: WON when `auction_winner` row exists; OUTBID when auction CLOSED and no winner; ACTIVE otherwise

### Flyway migrations

- V1 → V2 → V3 applied cleanly to an empty schema
- V3 drops `auction_summary` and `bid_activity` correctly before creating new tables
- All indexes and unique constraints present after migration

### Redis Stream consumers (end-to-end write path)

Using an embedded Redis (e.g. `testcontainers-redis`), publish an event to a stream and assert
the PostgreSQL state after the consumer processes it:

- `bid_placed` → `bid` row inserted, `auction.bid_count` incremented
- `bid_placed` redelivered → row not duplicated, count not incremented again
- `auction:closed` → `auction.status = CLOSED`, `auction_winner` rows created
- `auction:closed` redelivered → no duplicate winner rows
- `payment:processed` → `auction_winner.payment_status = COMPLETED`

---

## End-to-End Tests

### `scripts/smoke-test.sh`

Runs automatically after `docker-compose up`. Exercises the full request flow across all services:

1. Register a seller and a buyer
2. Create a shop and an item
3. Create an auction
4. Place a bid
5. Verify bid count and current highest via auction-service
6. Verify query-service read model reflects the bid
7. Wait for auction close and payment processing
8. Verify final state (CLOSED, payment created, stream consumer lag = 0)

```bash
docker-compose up --build -d
bash scripts/smoke-test.sh
```

---

## Performance / Load Tests

Results: [`loadtest/RESULTS.md`](../loadtest/RESULTS.md)

```bash
bash loadtest/run.sh          # all tests
bash loadtest/run.sh test1    # single-auction concurrency
bash loadtest/run.sh test2    # multi-auction throughput
bash loadtest/run.sh test3    # full lifecycle + consistency
```

> **Note**: Current results in `RESULTS.md` were captured before the Redisson lock was removed.
> Post-optimization Test 1 latency is expected to be significantly lower.

### Test 1 — Single Auction, Ramp-Up Concurrency

| Parameter | Value |
|-----------|-------|
| Auction setup | 1 auction, quantity=1, endTime=+5min |
| Bidders | 50 pre-registered buyers |
| Concurrency | 10 → 25 → 50 concurrent threads |
| Duration | 15s per level |
| Measured | p50/p95/p99 latency, throughput, error rate, peak CPU/mem |

### Test 2 — Multiple Auctions, Parallel Bidding

| Parameter | Value |
|-----------|-------|
| Auction setup | 20 auctions, quantity=3 each |
| Bidders | 100 pre-registered buyers |
| Concurrency | 50 concurrent threads |
| Duration | 30s |
| Measured | p50/p95/p99 latency, throughput, error rate, peak CPU/mem |

### Test 3 — Full Auction Lifecycle

| Parameter | Value |
|-----------|-------|
| Auctions | 10 auctions, endTime=+30s |
| Bidders | 30 buyers, 20 concurrent |
| After close | Verify payments, Redis cleanup, consumer lag |

### Metrics

| Metric | Source |
|--------|--------|
| HTTP latency p50/p95/p99 | Per-request `curl` timing in `run.sh` |
| Bid duration (Lua execution) | Micrometer — `GET /actuator/metrics/auction.bid.duration` |
| CPU / Memory per container | `docker stats`, sampled every 2s |

---

## Priority: What to Write Next

| Priority | Target | Estimated impact | Status |
|----------|--------|-----------------|--------|
| 1 | **`concurrency` — BidManager unit tests** | Covers the core bid-placement engine (was 9%). Pure Java, no real Redis needed; mock the Lua result and test the Java orchestration around it. | ✅ Done — 94% instruction coverage |
| 2 | **`query-service` controller WebMvcTest** | Was 0% on all query controllers. Same `@Import(SecurityConfig.class)` + JWT token pattern as auction-service controller tests. | ✅ Done — 30% instruction coverage (EnrichmentService mocked) |
| 3 | **`EnrichmentService` unit tests** | 174 lines of HTTP-client enrichment logic. Repo cache-hit and null-guard paths fully covered. HTTP fallback path (`fetchUserFallback` / `fetchItemFallback`) not covered — needs WireMock or a refactor to inject `HttpClient`. | ✅ Done (partial) — 70% controller coverage |
| 4 | **`JwtUtil` + `JwtAuthFilter` (shared)** | 12 tests covering token generate/validate round-trip, key padding, tampering/expiry rejection, filter pass-through and 401 path. Covers all services' shared security layer. | ✅ Done — ~100% security package |
| 5 | **`PaymentService` + `RecoveryJob`** | 23 tests covering the full payment state machine (PENDING→COMPLETED/FAILED), idempotent gateway decision reuse, max-retry abandon, and loop error isolation. | ✅ Done — 90% service package |
| 6 | **`UserService` + `ShopService`** | user-service and shop-service service layer. 11 + 14 tests covering all happy paths and error paths. | ✅ Done — 100% service package both modules |
| 7 | **Lua script tests** | 11 tests covering all script branches: AUCTION_CLOSED, BID_TOO_LOW (equal + below), PRICE_TOO_HIGH, no ceiling (maxPrice=0), first bid below capacity, eviction at capacity, floor recalculation, same-bidder score update, atomic version/bid_count, winners snapshot at indices 6+. | ✅ Done — direct Jedis + localhost Redis; skips gracefully if Redis unavailable |
| 8 | **Controller WebMvcTests (user, shop, payment)** | 39 tests across 3 new `*ControllerTest` classes. Covers auth/role enforcement (401/403), Bean Validation (400), happy paths, principal injection (`/me` endpoints), and public permitAll routes. | ✅ Done — same `@Import(SecurityConfig.class)` + mocked JWT Claims pattern as auction-service |
| 9 | **`BidRepository` native query integration tests** | SQL written by hand; wrong queries fail silently at runtime. Use Testcontainers PostgreSQL. | 🔲 Not started |
| 10 | **`BidPlacedConsumer` idempotency** | Covers the most common failure mode (PEL redelivery). Already partially covered in `BidPlacedConsumerTest`; duplicate-handling path needs a redelivery scenario. | 🔲 Not started |
| 11 | **`SecurityConfig` 401 fix in all services** | Apply `HttpStatusEntryPoint(UNAUTHORIZED)` to every service's `SecurityConfig`. | ✅ Done — confirmed in all 6 services |
