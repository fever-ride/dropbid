package com.dropbid.query.events;

import com.dropbid.query.model.AuctionSummary;
import com.dropbid.query.repository.AuctionSummaryRepository;
import com.dropbid.shared.events.AuctionCreatedEvent;
import com.dropbid.shared.streaming.ResilientStreamConsumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Consumes {@code auction:created} events and bootstraps the
 * {@code auction_summary} row with structural fields that {@code bid_placed}
 * events do not carry: endTime, quantity, startingBid, status.
 *
 * <p>Idempotency: the handler is safe to re-run.  On the first delivery it
 * creates the row; on redelivery it updates structural fields only, leaving
 * bidCount, lastBidId, and closedAt untouched so that any bid_placed events
 * already processed are not overwritten.
 */
@Component
public class AuctionCreatedConsumer extends ResilientStreamConsumer {

    private final AuctionSummaryRepository auctionRepo;
    private final ObjectMapper mapper;

    public AuctionCreatedConsumer(StringRedisTemplate redis,
                                   AuctionSummaryRepository auctionRepo,
                                   ObjectMapper mapper) {
        super(redis);
        this.auctionRepo = auctionRepo;
        this.mapper = mapper;
    }

    @Override protected String stream()       { return "auction:created"; }
    @Override protected String group()        { return "query-service"; }
    @Override protected String consumerName() { return "query-created-consumer-1"; }

    @Override
    protected void handleMessage(MapRecord<String, Object, Object> record) {
        try {
            String json = (String) record.getValue().get("data");
            AuctionCreatedEvent event = mapper.readValue(json, AuctionCreatedEvent.class);
            handleAuctionCreated(event);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional
    public void handleAuctionCreated(AuctionCreatedEvent event) {
        AuctionSummary summary = auctionRepo.findById(event.auctionId())
                .orElseGet(() -> {
                    AuctionSummary s = new AuctionSummary();
                    s.setAuctionId(event.auctionId());
                    return s;
                });

        // Always sync structural fields from the authoritative auction:created event.
        // bidCount, lastBidId, and closedAt are owned by bid_placed / auction:closed
        // consumers and must not be reset here.
        summary.setItemId(event.itemId());
        summary.setShopId(event.shopId());
        summary.setSellerId(event.sellerId());
        summary.setStatus(event.status());
        summary.setEndTime(event.endTime());
        summary.setQuantity(event.quantity());

        // currentHighest starts at startingBid for a brand-new row;
        // leave it alone on redelivery so existing bids are not overwritten.
        if (summary.getCurrentHighest() == 0) {
            summary.setCurrentHighest(event.startingBid());
        }
        summary.setUpdatedAt(Instant.now());

        auctionRepo.save(summary);
    }
}
