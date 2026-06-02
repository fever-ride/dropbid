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
 * Unit tests for {@link AuctionOpener#checkAndOpenAuctions}.
 *
 * No Spring context — the scheduler only delegates to {@link AuctionService};
 * it is instantiated directly with a mock service.
 */
@ExtendWith(MockitoExtension.class)
class AuctionOpenerTest {

    @Mock AuctionService service;

    AuctionOpener opener;

    @BeforeEach
    void setUp() {
        opener = new AuctionOpener(service);
    }

    /** Due auctions are opened exactly once each. */
    @Test
    void checkAndOpenAuctions_withDueAuctions_openEach() {
        when(service.pollDueAuctionIds(AuctionService.SCHEDULE_OPEN))
                .thenReturn(Set.of("a1", "a2"));

        opener.checkAndOpenAuctions();

        verify(service).openAuction("a1");
        verify(service).openAuction("a2");
    }

    /** Empty poll result — openAuction is never called. */
    @Test
    void checkAndOpenAuctions_noDueAuctions_doesNotCallOpen() {
        when(service.pollDueAuctionIds(AuctionService.SCHEDULE_OPEN))
                .thenReturn(Set.of());

        opener.checkAndOpenAuctions();

        verify(service, never()).openAuction(any());
    }

    /**
     * If pollDueAuctionIds throws (e.g. Redis unavailable), the exception is
     * swallowed and does not propagate — the scheduler tick must not crash.
     */
    @Test
    void checkAndOpenAuctions_serviceThrows_doesNotPropagateException() {
        when(service.pollDueAuctionIds(AuctionService.SCHEDULE_OPEN))
                .thenThrow(new RuntimeException("Redis down"));

        assertDoesNotThrow(() -> opener.checkAndOpenAuctions());
        verify(service, never()).openAuction(any());
    }

    /**
     * If openAuction throws for one auction, the exception is swallowed
     * (outer try/catch in checkAndOpenAuctions) and the tick completes.
     */
    @Test
    void checkAndOpenAuctions_openThrows_doesNotPropagateException() {
        when(service.pollDueAuctionIds(AuctionService.SCHEDULE_OPEN))
                .thenReturn(Set.of("a-bad"));
        doThrow(new RuntimeException("DynamoDB error")).when(service).openAuction("a-bad");

        assertDoesNotThrow(() -> opener.checkAndOpenAuctions());
    }
}
