package com.dropbid.auction.scheduler;

import com.dropbid.auction.model.Auction;
import com.dropbid.auction.service.AuctionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Background scheduler that polls PENDING auctions every second and opens
 * those whose startTime has arrived — seeding the Redis hot-path cache and
 * flipping status to OPEN so bids can flow in.
 */
@Component
public class AuctionOpener {

    private static final Logger log = LoggerFactory.getLogger(AuctionOpener.class);

    private final AuctionService service;

    public AuctionOpener(AuctionService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${auction.opener-interval-ms:1000}")
    public void checkAndOpenAuctions() {
        try {
            List<Auction> pending = service.listAuctions("PENDING");
            for (Auction auction : pending) {
                if (service.isReadyToOpen(auction)) {
                    log.info("opening scheduled auction {}", auction.getAuctionId());
                    service.openAuction(auction.getAuctionId());
                }
            }
        } catch (Exception e) {
            log.error("error in AuctionOpener tick: {}", e.getMessage(), e);
        }
    }
}
