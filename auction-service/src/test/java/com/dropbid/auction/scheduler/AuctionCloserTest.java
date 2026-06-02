package com.dropbid.auction.scheduler;

import com.dropbid.auction.service.AuctionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AuctionCloser#checkAndCloseAuctions}.
 *
 * Pure unit — no Spring context needed; the scheduler only delegates to
 * {@link AuctionService}, which is mocked.
 */
@ExtendWith(MockitoExtension.class)
class AuctionCloserTest {

    @Mock AuctionService service;

    AuctionCloser closer;

    @BeforeEach
    void setUp() {
        closer = new AuctionCloser(service);
    }

    /** Expired auctions are closed exactly once each. */
    @Test
    void checkAndCloseAuctions_withExpiredAuctions_closeEach() {
        when(service.pollDueAuctionIds(AuctionService.SCHEDULE_CLOSE))
                .thenReturn(Set.of("a1", "a2", "a3"));

        closer.checkAndCloseAuctions();

        verify(service).closeAuction("a1");
        verify(service).closeAuction("a2");
        verify(service).closeAuction("a3");
    }

    /** Empty poll result — closeAuction is never called. */
    @Test
    void checkAndCloseAuctions_noDueAuctions_doesNotCallClose() {
        when(service.pollDueAuctionIds(AuctionService.SCHEDULE_CLOSE))
                .thenReturn(Set.of());

        closer.checkAndCloseAuctions();

        verify(service, never()).closeAuction(any());
    }

    /**
     * Redis/service failure during poll must not crash the scheduler tick.
     */
    @Test
    void checkAndCloseAuctions_pollThrows_doesNotPropagateException() {
        when(service.pollDueAuctionIds(AuctionService.SCHEDULE_CLOSE))
                .thenThrow(new RuntimeException("Redis unavailable"));

        assertDoesNotThrow(() -> closer.checkAndCloseAuctions());
        verify(service, never()).closeAuction(any());
    }

    /**
     * A failure closing one auction must not prevent the scheduler from
     * completing the tick (exception is swallowed by the outer try/catch).
     */
    @Test
    void checkAndCloseAuctions_closeThrows_doesNotPropagateException() {
        when(service.pollDueAuctionIds(AuctionService.SCHEDULE_CLOSE))
                .thenReturn(Set.of("a-fail"));
        doThrow(new RuntimeException("DynamoDB timeout")).when(service).closeAuction("a-fail");

        assertDoesNotThrow(() -> closer.checkAndCloseAuctions());
    }
}
