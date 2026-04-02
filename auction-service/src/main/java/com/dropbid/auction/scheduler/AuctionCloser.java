package com.dropbid.auction.scheduler;

import com.dropbid.auction.model.Auction;
import com.dropbid.auction.service.AuctionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Background scheduler that polls for expired OPEN auctions every second
 * and closes them — triggering the payment flow via Redis Streams.
 * Mirrors the Go ticker-based closer.go.
 */
@Component
public class AuctionCloser {

    private static final Logger log = LoggerFactory.getLogger(AuctionCloser.class);

    private final AuctionService service;

    public AuctionCloser(AuctionService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${auction.closer-interval-ms:1000}")
    public void checkAndCloseAuctions() {
        try {
            List<Auction> open = service.listAuctions("OPEN");
            for (Auction auction : open) {
                if (service.isExpired(auction)) {
                    log.info("closing expired auction {}", auction.getAuctionId());
                    service.closeAuction(auction.getAuctionId());
                }
            }
        } catch (Exception e) {
            // Log but never crash the scheduler
            log.error("error in AuctionCloser tick: {}", e.getMessage(), e);
        }
    }
}
