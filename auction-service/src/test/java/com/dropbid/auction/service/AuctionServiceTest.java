package com.dropbid.auction.service;

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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuctionServiceTest {

    private List<Auction> store;
    private AuctionStore repo;
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

        service = new AuctionService(repo, redis, redisson, publisher, strategyManager);
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
}
