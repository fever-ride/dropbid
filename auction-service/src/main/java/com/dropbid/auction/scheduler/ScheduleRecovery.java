package com.dropbid.auction.scheduler;

import com.dropbid.auction.service.AuctionService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class ScheduleRecovery {

    private final AuctionService service;

    public ScheduleRecovery(AuctionService service) {
        this.service = service;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        service.rebuildSchedules();
    }
}
