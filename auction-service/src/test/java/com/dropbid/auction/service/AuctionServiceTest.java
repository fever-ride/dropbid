package com.dropbid.auction.service;

import com.dropbid.auction.bid.repository.BidStore;
import com.dropbid.auction.concurrency.BidResult;
import com.dropbid.auction.concurrency.BidStrategy;
import com.dropbid.auction.concurrency.StrategyManager;
import com.dropbid.auction.events.AuctionEventPublisher;
import com.dropbid.auction.model.Auction;
import com.dropbid.auction.repository.AuctionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuctionServiceTest {

    private List<Auction> store;
    private AuctionStore repo;
    private BidStore bidStore;
    private StringRedisTemplate redis;
    private RedissonClient redisson;
    private AuctionEventPublisher publisher;
    private StrategyManager strategyManager;
    private AuctionService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        store = new ArrayList<>();

        repo = new AuctionStore() {
            @Override public void save(Auction a) { store.add(a); }
            @Override public Auction findById(String id) {
                return store.stream().filter(a -> a.getAuctionId().equals(id)).findFirst().orElse(null);
            }
            @Override public Auction findByIdOrNull(String id) { return findById(id); }
            @Override public List<Auction> findByStatus(String status) {
                return store.stream().filter(a -> status.equals(a.getStatus())).toList();
            }
            @Override public void update(Auction a) { /* in-place mutation */ }
            @Override public void updateUnconditional(Auction a) { /* in-place mutation */ }
        };

        redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zSetOps = mock(ZSetOperations.class);
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
        when(redis.opsForZSet()).thenReturn(zSetOps);
        when(redis.opsForHash()).thenReturn(hashOps);

        redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        publisher = mock(AuctionEventPublisher.class);
        strategyManager = mock(StrategyManager.class);
        bidStore = mock(BidStore.class);

        service = new AuctionService(repo, bidStore, redis, redisson, publisher, strategyManager);
    }

    // ── createAuction validation ────────────────────────────────────────────

    @Test
    void createAuction_maxPriceLessThanStartingBid_throws() {
        String future = Instant.now().plus(1, ChronoUnit.HOURS).toString();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                service.createAuction("seller1", "shop1", "item1", 500, 400L, null, future, 1));
        assertTrue(ex.getReason().contains("maxPrice must exceed startingBid"));
    }

    @Test
    void createAuction_maxPriceEqualToStartingBid_throws() {
        String future = Instant.now().plus(1, ChronoUnit.HOURS).toString();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                service.createAuction("seller1", "shop1", "item1", 500, 500L, null, future, 1));
        assertTrue(ex.getReason().contains("maxPrice must exceed startingBid"));
    }

    @Test
    void createAuction_endTimeInPast_throws() {
        String past = Instant.now().minus(1, ChronoUnit.HOURS).toString();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                service.createAuction("seller1", "shop1", "item1", 100, null, null, past, 1));
        assertTrue(ex.getReason().contains("endTime must be in the future"));
    }

    @Test
    void createAuction_endTimeBeforeStartTime_throws() {
        String start = Instant.now().plus(2, ChronoUnit.HOURS).toString();
        String end = Instant.now().plus(1, ChronoUnit.HOURS).toString();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                service.createAuction("seller1", "shop1", "item1", 100, null, start, end, 1));
        assertTrue(ex.getReason().contains("endTime must be after startTime"));
    }

    @Test
    void createAuction_noStartTime_opensImmediately() {
        String end = Instant.now().plus(1, ChronoUnit.HOURS).toString();

        Auction result = service.createAuction("seller1", "shop1", "item1", 100, 500L, null, end, 1);

        assertEquals("OPEN", result.getStatus());
        assertEquals(100L, result.getStartingBid());
        assertEquals(500L, result.getMaxPrice());
        assertEquals(100L, result.getCurrentHighest());
        assertEquals(0L, result.getBidCount());
        assertEquals(0L, result.getVersion());
        assertNotNull(result.getAuctionId());
        assertEquals(1, store.size());
    }

    @Test
    void createAuction_withFutureStartTime_createsPending() {
        String start = Instant.now().plus(1, ChronoUnit.HOURS).toString();
        String end = Instant.now().plus(2, ChronoUnit.HOURS).toString();

        Auction result = service.createAuction("seller1", "shop1", "item1", 100, null, start, end, 1);

        assertEquals("PENDING", result.getStatus());
        assertEquals(start, result.getStartTime());
    }

    @Test
    void createAuction_nullMaxPrice_succeeds() {
        String end = Instant.now().plus(1, ChronoUnit.HOURS).toString();

        Auction result = service.createAuction("seller1", "shop1", "item1", 100, null, null, end, 1);

        assertEquals("OPEN", result.getStatus());
        assertNull(result.getMaxPrice());
    }

    @Test
    void createAuction_quantitySetCorrectly() {
        String end = Instant.now().plus(1, ChronoUnit.HOURS).toString();

        Auction result = service.createAuction("seller1", "shop1", "item1", 100, null, null, end, 5);

        assertEquals(5L, result.getQuantity());
    }

    // ── isExpired / isReadyToOpen ───────────────────────────────────────────

    @Test
    void isExpired_endTimeInPast_true() {
        Auction a = new Auction();
        a.setEndTime(Instant.now().minus(1, ChronoUnit.HOURS).toString());
        assertTrue(service.isExpired(a));
    }

    @Test
    void isExpired_endTimeInFuture_false() {
        Auction a = new Auction();
        a.setEndTime(Instant.now().plus(1, ChronoUnit.HOURS).toString());
        assertFalse(service.isExpired(a));
    }

    @Test
    void isExpired_invalidEndTime_false() {
        Auction a = new Auction();
        a.setEndTime("not-a-date");
        assertFalse(service.isExpired(a));
    }

    @Test
    void isReadyToOpen_startTimeInPast_true() {
        Auction a = new Auction();
        a.setStartTime(Instant.now().minus(1, ChronoUnit.MINUTES).toString());
        assertTrue(service.isReadyToOpen(a));
    }

    @Test
    void isReadyToOpen_startTimeInFuture_false() {
        Auction a = new Auction();
        a.setStartTime(Instant.now().plus(1, ChronoUnit.HOURS).toString());
        assertFalse(service.isReadyToOpen(a));
    }

    @Test
    void isReadyToOpen_invalidStartTime_false() {
        Auction a = new Auction();
        a.setStartTime("garbage");
        assertFalse(service.isReadyToOpen(a));
    }

    // ── openAuction ─────────────────────────────────────────────────────────

    /**
     * Bug-2 regression: seedRedisCache must repopulate the winners ZSET from the
     * DynamoDB snapshot.  Without this, a Redis restart mid-auction causes the Lua
     * script to see an empty ZSET and accept bids below the real floor price.
     */
    @Test
    void openAuction_restoresWinnersZsetFromDynamoSnapshot() {
        String start = Instant.now().plus(1, ChronoUnit.HOURS).toString();
        String end   = Instant.now().plus(2, ChronoUnit.HOURS).toString();
        Auction a = service.createAuction("seller1", "shop1", "item1", 100, null, start, end, 3);

        // Simulate a DynamoDB snapshot that already has two winners (e.g. state before Redis restart)
        a.setWinners(Map.of("buyerA", 15000L, "buyerB", 12000L));

        service.openAuction(a.getAuctionId());

        String expectedWinnersKey = "auction:" + a.getAuctionId() + ":winners";
        verify(redis.opsForZSet()).add(expectedWinnersKey, "buyerA", 15000.0);
        verify(redis.opsForZSet()).add(expectedWinnersKey, "buyerB", 12000.0);
    }

    @Test
    void openAuction_pendingAuction_becomesOpen() {
        String end = Instant.now().plus(2, ChronoUnit.HOURS).toString();
        String start = Instant.now().plus(1, ChronoUnit.HOURS).toString();
        Auction a = service.createAuction("seller1", "shop1", "item1", 100, null, start, end, 1);
        assertEquals("PENDING", a.getStatus());

        service.openAuction(a.getAuctionId());

        assertEquals("OPEN", a.getStatus());
    }

    @Test
    void openAuction_alreadyOpen_noChange() {
        String end = Instant.now().plus(1, ChronoUnit.HOURS).toString();
        Auction a = service.createAuction("seller1", "shop1", "item1", 100, null, null, end, 1);
        assertEquals("OPEN", a.getStatus());

        service.openAuction(a.getAuctionId());

        assertEquals("OPEN", a.getStatus());
    }

    @Test
    void openAuction_unknownId_noException() {
        assertDoesNotThrow(() -> service.openAuction("nonexistent"));
    }

    // ── closeAuction ────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    @Test
    void closeAuction_openAuction_becomesClosed() {
        String end = Instant.now().plus(1, ChronoUnit.HOURS).toString();
        Auction a = service.createAuction("seller1", "shop1", "item1", 100, null, null, end, 1);
        a.setHighestBidder("buyerA");
        a.setCurrentHighest(500L);

        when(redis.opsForZSet().rangeWithScores(anyString(), anyLong(), anyLong()))
                .thenReturn(Set.of());

        service.closeAuction(a.getAuctionId());

        assertEquals("CLOSED", a.getStatus());
    }

    /**
     * Bug-1 regression: closeAuction must write the authoritative winners back to
     * the DynamoDB record so that BidController.resolveWinners() returns correct
     * data after the Redis keys have been deleted.
     */
    @SuppressWarnings("unchecked")
    @Test
    void closeAuction_persistsWinnersToAuctionRecord() {
        String end = Instant.now().plus(1, ChronoUnit.HOURS).toString();
        Auction a = service.createAuction("seller1", "shop1", "item1", 100, null, null, end, 1);

        ZSetOperations.TypedTuple<String> tuple = mock(ZSetOperations.TypedTuple.class);
        when(tuple.getValue()).thenReturn("buyerA");
        when(tuple.getScore()).thenReturn(15000.0);
        when(redis.opsForZSet().rangeWithScores(anyString(), anyLong(), anyLong()))
                .thenReturn(Set.of(tuple));

        service.closeAuction(a.getAuctionId());

        assertEquals("CLOSED", a.getStatus());
        assertNotNull(a.getWinners(), "winners must be persisted so the closed auction record is authoritative");
        assertEquals(15000L, a.getWinners().get("buyerA"));
    }

    @Test
    void closeAuction_unknownId_noException() {
        when(redis.opsForZSet().rangeWithScores(anyString(), anyLong(), anyLong()))
                .thenReturn(Set.of());
        assertDoesNotThrow(() -> service.closeAuction("nonexistent"));
    }

    @Test
    void closeAuction_alreadyClosed_noChange() {
        String end = Instant.now().plus(1, ChronoUnit.HOURS).toString();
        Auction a = service.createAuction("seller1", "shop1", "item1", 100, null, null, end, 1);
        a.setStatus("CLOSED");

        service.closeAuction(a.getAuctionId());

        assertEquals("CLOSED", a.getStatus());
        verify(publisher, never()).publishAuctionClosed(any());
    }

    // ── placeBid: seller cannot bid ─────────────────────────────────────────

    @Test
    void placeBid_sellerBidsOwnAuction_throws() {
        String end = Instant.now().plus(1, ChronoUnit.HOURS).toString();
        Auction a = service.createAuction("seller1", "shop1", "item1", 100, null, null, end, 1);

        when(redis.hasKey("auction:" + a.getAuctionId())).thenReturn(true);
        when(redis.opsForHash().get("auction:" + a.getAuctionId(), "seller_id")).thenReturn("seller1");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                service.placeBid(a.getAuctionId(), 200, "seller1"));
        assertTrue(ex.getReason().contains("seller cannot bid"));
    }

    // ── listAuctions ────────────────────────────────────────────────────────

    @Test
    void listAuctions_defaultsToOpen() {
        String end = Instant.now().plus(1, ChronoUnit.HOURS).toString();
        service.createAuction("seller1", "shop1", "item1", 100, null, null, end, 1);
        String start = Instant.now().plus(1, ChronoUnit.HOURS).toString();
        String end2 = Instant.now().plus(2, ChronoUnit.HOURS).toString();
        service.createAuction("seller2", "shop2", "item2", 200, null, start, end2, 1);

        List<Auction> open = service.listAuctions(null);
        assertEquals(1, open.size());
        assertEquals("OPEN", open.get(0).getStatus());
    }

    @Test
    void listAuctions_filterByPending() {
        String start = Instant.now().plus(1, ChronoUnit.HOURS).toString();
        String end = Instant.now().plus(2, ChronoUnit.HOURS).toString();
        service.createAuction("seller1", "shop1", "item1", 100, null, start, end, 1);

        List<Auction> pending = service.listAuctions("PENDING");
        assertEquals(1, pending.size());
    }

    // ── createAuction: event publishing ─────────────────────────────────────

    @Test
    void createAuction_open_publishesAuctionCreatedEvent() {
        String end = Instant.now().plus(1, ChronoUnit.HOURS).toString();
        Auction result = service.createAuction("seller1", "shop1", "item1", 100, null, null, end, 2);

        verify(publisher).publishAuctionCreated(argThat(e ->
                e.auctionId().equals(result.getAuctionId())
                && "OPEN".equals(e.status())
                && e.startingBid() == 100
                && e.quantity() == 2
        ));
    }

    @Test
    void createAuction_pending_publishesAuctionCreatedEventWithPendingStatus() {
        String start = Instant.now().plus(1, ChronoUnit.HOURS).toString();
        String end   = Instant.now().plus(2, ChronoUnit.HOURS).toString();
        Auction result = service.createAuction("seller1", "shop1", "item1", 100, null, start, end, 1);

        verify(publisher).publishAuctionCreated(argThat(e ->
                e.auctionId().equals(result.getAuctionId())
                && "PENDING".equals(e.status())
        ));
    }

    // ── openAuction: event publishing ───────────────────────────────────────

    @Test
    void openAuction_pendingAuction_publishesAuctionCreatedWithOpenStatus() {
        String start = Instant.now().plus(1, ChronoUnit.HOURS).toString();
        String end   = Instant.now().plus(2, ChronoUnit.HOURS).toString();
        Auction a = service.createAuction("seller1", "shop1", "item1", 100, null, start, end, 1);

        // clear the invocation from createAuction so we can verify openAuction's call separately
        clearInvocations(publisher);

        service.openAuction(a.getAuctionId());

        verify(publisher).publishAuctionCreated(argThat(e ->
                e.auctionId().equals(a.getAuctionId())
                && "OPEN".equals(e.status())
        ));
    }

    @Test
    void openAuction_alreadyOpen_doesNotPublishEvent() {
        String end = Instant.now().plus(1, ChronoUnit.HOURS).toString();
        Auction a = service.createAuction("seller1", "shop1", "item1", 100, null, null, end, 1);
        clearInvocations(publisher);

        service.openAuction(a.getAuctionId());

        verify(publisher, never()).publishAuctionCreated(any());
    }

    // ── closeAuction: winners resolution ────────────────────────────────────

    @SuppressWarnings("unchecked")
    @Test
    void closeAuction_multipleWinnersFromZset_allPersistedAndPublished() {
        String end = Instant.now().plus(1, ChronoUnit.HOURS).toString();
        Auction a = service.createAuction("seller1", "shop1", "item1", 100, null, null, end, 2);

        ZSetOperations.TypedTuple<String> t1 = mock(ZSetOperations.TypedTuple.class);
        when(t1.getValue()).thenReturn("buyerA");
        when(t1.getScore()).thenReturn(500.0);

        ZSetOperations.TypedTuple<String> t2 = mock(ZSetOperations.TypedTuple.class);
        when(t2.getValue()).thenReturn("buyerB");
        when(t2.getScore()).thenReturn(300.0);

        when(redis.opsForZSet().rangeWithScores(anyString(), anyLong(), anyLong()))
                .thenReturn(Set.of(t1, t2));

        service.closeAuction(a.getAuctionId());

        assertEquals("CLOSED", a.getStatus());
        assertNotNull(a.getWinners());
        assertEquals(2, a.getWinners().size());
        assertEquals(500L, a.getWinners().get("buyerA"));
        assertEquals(300L, a.getWinners().get("buyerB"));
        verify(publisher).publishAuctionClosed(argThat(e ->
                e.winners() != null && e.winners().size() == 2
        ));
    }

    @SuppressWarnings("unchecked")
    @Test
    void closeAuction_zsetEmpty_fallsBackToDynamoWinners() {
        String end = Instant.now().plus(1, ChronoUnit.HOURS).toString();
        Auction a = service.createAuction("seller1", "shop1", "item1", 100, null, null, end, 1);
        a.setWinners(Map.of("buyerA", 400L));

        when(redis.opsForZSet().rangeWithScores(anyString(), anyLong(), anyLong()))
                .thenReturn(Set.of());

        service.closeAuction(a.getAuctionId());

        assertEquals("CLOSED", a.getStatus());
        assertEquals(400L, a.getWinners().get("buyerA"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void closeAuction_zsetAndDynamoEmpty_fallsBackToHighestBidder() {
        String end = Instant.now().plus(1, ChronoUnit.HOURS).toString();
        Auction a = service.createAuction("seller1", "shop1", "item1", 100, null, null, end, 1);
        a.setHighestBidder("buyerZ");
        a.setCurrentHighest(700L);

        when(redis.opsForZSet().rangeWithScores(anyString(), anyLong(), anyLong()))
                .thenReturn(Set.of());

        service.closeAuction(a.getAuctionId());

        assertEquals("CLOSED", a.getStatus());
        assertEquals(700L, a.getWinners().get("buyerZ"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void closeAuction_noBidAuction_publishesEventWithNullWinners() {
        String end = Instant.now().plus(1, ChronoUnit.HOURS).toString();
        Auction a = service.createAuction("seller1", "shop1", "item1", 100, null, null, end, 1);

        when(redis.opsForZSet().rangeWithScores(anyString(), anyLong(), anyLong()))
                .thenReturn(Set.of());

        service.closeAuction(a.getAuctionId());

        assertEquals("CLOSED", a.getStatus());
        assertNull(a.getWinners());
        // event still published — query-service needs it to transition status to CLOSED
        verify(publisher).publishAuctionClosed(argThat(e ->
                e.auctionId().equals(a.getAuctionId()) && e.winners() == null
        ));
    }

    // ── getAuction ───────────────────────────────────────────────────────────

    @Test
    void getAuction_existingId_returnsAuction() {
        String end = Instant.now().plus(1, ChronoUnit.HOURS).toString();
        Auction created = service.createAuction("seller1", "shop1", "item1", 100, null, null, end, 1);

        Auction found = service.getAuction(created.getAuctionId());

        assertNotNull(found);
        assertEquals(created.getAuctionId(), found.getAuctionId());
    }

    @Test
    void getAuction_unknownId_returnsNull() {
        assertNull(service.getAuction("does-not-exist"));
    }

    // ── pollDueAuctionIds ────────────────────────────────────────────────────

    @Test
    void pollDueAuctionIds_returnsDueIds() {
        Set<String> expected = Set.of("auction-1", "auction-2");
        when(redis.opsForZSet().rangeByScore(eq("auction:schedule:close"), eq(0.0), anyDouble()))
                .thenReturn(expected);

        Set<String> result = service.pollDueAuctionIds("auction:schedule:close");

        assertEquals(expected, result);
    }

    @Test
    void pollDueAuctionIds_nullFromRedis_returnsEmptySet() {
        when(redis.opsForZSet().rangeByScore(anyString(), anyDouble(), anyDouble()))
                .thenReturn(null);

        Set<String> result = service.pollDueAuctionIds("auction:schedule:close");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ── rebuildSchedules ─────────────────────────────────────────────────────

    @Test
    void rebuildSchedules_addsSchedulesToSortedSets() {
        // One PENDING, one OPEN already in store via createAuction
        String start  = Instant.now().plus(1, ChronoUnit.HOURS).toString();
        String end    = Instant.now().plus(2, ChronoUnit.HOURS).toString();
        String endNow = Instant.now().plus(1, ChronoUnit.HOURS).toString();

        Auction pending = service.createAuction("s1", "shop1", "item1", 100, null, start, end, 1);
        Auction open    = service.createAuction("s2", "shop2", "item2", 200, null, null, endNow, 1);

        // Clear invocations recorded during createAuction so we only count rebuildSchedules calls
        clearInvocations(redis.opsForZSet());

        // Simulate Redis already having the open auction's cache so seedRedisCache is not triggered
        when(redis.hasKey("auction:" + open.getAuctionId())).thenReturn(true);

        service.rebuildSchedules();

        // PENDING auction → SCHEDULE_OPEN
        verify(redis.opsForZSet()).add(
                eq("auction:schedule:open"),
                eq(pending.getAuctionId()),
                anyDouble()
        );
        // OPEN auction → SCHEDULE_CLOSE
        verify(redis.opsForZSet()).add(
                eq("auction:schedule:close"),
                eq(open.getAuctionId()),
                anyDouble()
        );
    }

    @Test
    void rebuildSchedules_warmsCacheForOpenAuctionWithMissingKey() {
        String end = Instant.now().plus(1, ChronoUnit.HOURS).toString();
        Auction open = service.createAuction("s1", "shop1", "item1", 100, null, null, end, 1);

        // Clear invocations recorded during createAuction so we only count rebuildSchedules calls
        clearInvocations(redis.opsForHash());

        // Simulate the hash key is absent → seedRedisCache should be called
        when(redis.hasKey("auction:" + open.getAuctionId())).thenReturn(false);

        service.rebuildSchedules();

        // seedRedisCache calls putAll with the auction state
        verify(redis.opsForHash()).putAll(eq("auction:" + open.getAuctionId()), any());
    }

    // ── placeBid: async bid history ──────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void placeBid_success_writesBidHistoryAsynchronously() throws Exception {
        String end = Instant.now().plus(1, ChronoUnit.HOURS).toString();
        Auction a = service.createAuction("seller1", "shop1", "item1", 100, null, null, end, 1);
        String auctionId = a.getAuctionId();

        BidResult result = new BidResult(auctionId, "buyer1", 300, 1L, 1L,
                null, 0L, 300L, "buyer1", Map.of("buyer1", 300L));

        BidStrategy mockStrategy = mock(BidStrategy.class);
        when(strategyManager.current()).thenReturn(mockStrategy);
        doReturn(result).when(mockStrategy).tryPlaceBid(eq(auctionId), eq(300L), eq("buyer1"));

        // Cache hit — skip rebuild
        when(redis.hasKey("auction:" + auctionId)).thenReturn(true);
        when(redis.opsForHash().get("auction:" + auctionId, "seller_id")).thenReturn("seller1");
        when(redis.opsForHash().get("auction:" + auctionId, "item_id")).thenReturn("item1");

        service.placeBid(auctionId, 300, "buyer1");

        // CompletableFuture runs on ForkJoinPool — wait up to 2s for the async write
        org.awaitility.Awaitility.await()
                .atMost(2, TimeUnit.SECONDS)
                .untilAsserted(() -> verify(bidStore).save(any()));
    }
}
