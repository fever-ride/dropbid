package com.dropbid.auction.scheduler;

import com.dropbid.auction.service.AuctionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

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
            Set<String> dueIds = service.pollDueAuctionIds(AuctionService.SCHEDULE_CLOSE);
            for (String auctionId : dueIds) {
                log.info("closing expired auction {}", auctionId);
                service.closeAuction(auctionId);
            }
        } catch (Exception e) {
            log.error("error in AuctionCloser tick: {}", e.getMessage(), e);
        }
    }
}
