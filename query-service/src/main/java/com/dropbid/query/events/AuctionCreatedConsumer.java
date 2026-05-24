package com.dropbid.query.events;

import com.dropbid.query.model.Auction;
import com.dropbid.query.repository.AuctionRepository;
import com.dropbid.shared.events.AuctionCreatedEvent;
import com.dropbid.shared.streaming.ResilientStreamConsumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Consumes {@code auction:created} events and upserts the {@code auction} row
 * with structural fields (endTime, quantity, startingBid, status, …) that
 * {@code bid_placed} events do not carry.
 *
 * <p>Published twice in the auction lifecycle:
 * <ol>
 *   <li>At creation — status is {@code OPEN} or {@code PENDING}</li>
 *   <li>When a PENDING auction opens — status is {@code OPEN}</li>
 * </ol>
 *
 * <p>Idempotency: the handler is safe to re-run.
 * <ul>
 *   <li>Structural fields are always overwritten with the authoritative event values.</li>
 *   <li>{@code status} is only updated if the stored value is not already {@code CLOSED}
 *       — prevents a late-arriving {@code auction:created} redelivery from reverting a
 *       closed auction back to OPEN if {@code auction:closed} was already processed.</li>
 *   <li>{@code currentHighest} is set to {@code startingBid} only for a brand-new row
 *       (value == 0), so any bid events already processed are not overwritten.</li>
 * </ul>
 */
@Component
public class AuctionCreatedConsumer extends ResilientStreamConsumer {

    private final AuctionRepository auctionRepo;
    private final ObjectMapper      mapper;

    public AuctionCreatedConsumer(StringRedisTemplate redis,
                                   AuctionRepository auctionRepo,
                                   ObjectMapper mapper) {
        super(redis);
        this.auctionRepo = auctionRepo;
        this.mapper      = mapper;
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
        Auction auction = auctionRepo.findById(event.auctionId())
                .orElseGet(() -> {
                    Auction a = new Auction();
                    a.setAuctionId(event.auctionId());
                    return a;
                });

        // Always sync structural fields from the authoritative event.
        auction.setItemId(event.itemId());
        auction.setShopId(event.shopId());
        auction.setSellerId(event.sellerId());
        auction.setStartingBid(event.startingBid());
        auction.setStartTime(event.startTime());
        auction.setEndTime(event.endTime());
        auction.setQuantity(event.quantity());

        // Never revert a CLOSED auction — auction:closed may have already arrived.
        if (!"CLOSED".equals(auction.getStatus())) {
            auction.setStatus(event.status());
        }

        // Set the floor price as the starting point for currentHighest only on a
        // brand-new row; leave it intact if bids have already been processed.
        if (auction.getCurrentHighest() == 0) {
            auction.setCurrentHighest(event.startingBid());
        }

        auction.setUpdatedAt(Instant.now());
        auctionRepo.save(auction);
    }
}
