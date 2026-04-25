package com.dropbid.auction.scheduler;

import com.dropbid.auction.service.AuctionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

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
            Set<String> dueIds = service.pollDueAuctionIds(AuctionService.SCHEDULE_OPEN);
            for (String auctionId : dueIds) {
                log.info("opening scheduled auction {}", auctionId);
                service.openAuction(auctionId);
            }
        } catch (Exception e) {
            log.error("error in AuctionOpener tick: {}", e.getMessage(), e);
        }
    }
}
