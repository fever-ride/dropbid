package com.dropbid.query.events;

import com.dropbid.query.model.Auction;
import com.dropbid.query.model.Bid;
import com.dropbid.query.repository.AuctionRepository;
import com.dropbid.query.repository.BidRepository;
import com.dropbid.shared.events.BidPlacedEvent;
import com.dropbid.shared.streaming.ResilientStreamConsumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Consumes {@code bid_placed} events and appends a row to the {@code bid} table,
 * then atomically increments the denormalised counters on the parent
 * {@code auction} row.
 *
 * <h3>Idempotency</h3>
 * {@code bidId} is the primary key of the {@code bid} table.  Before writing,
 * the handler checks {@code bidRepo.existsById(bidId)}.  If the row already
 * exists the message was a PEL redelivery — skip silently.  All of this runs
 * inside a single {@code @Transactional} so the check-then-insert is atomic
 * for the single-consumer process model.
 *
 * <h3>Out-of-order delivery</h3>
 * If a bid arrives before its {@code auction:created} event, a skeletal
 * {@code auction} row is created so the bid can be recorded.  The structural
 * fields (endTime, quantity, …) are filled in when {@code auction:created}
 * eventually arrives.
 */
@Component
public class BidPlacedConsumer extends ResilientStreamConsumer {

    private final AuctionRepository auctionRepo;
    private final BidRepository     bidRepo;
    private final ObjectMapper      mapper;

    public BidPlacedConsumer(StringRedisTemplate redis,
                              AuctionRepository auctionRepo,
                              BidRepository bidRepo,
                              ObjectMapper mapper) {
        super(redis);
        this.auctionRepo = auctionRepo;
        this.bidRepo     = bidRepo;
        this.mapper      = mapper;
    }

    @Override protected String stream()       { return "bid_placed"; }
    @Override protected String group()        { return "query-service"; }
    @Override protected String consumerName() { return "query-bid-consumer-1"; }

    @Override
    protected void handleMessage(MapRecord<String, Object, Object> record) {
        try {
            String json = (String) record.getValue().get("data");
            BidPlacedEvent event = mapper.readValue(json, BidPlacedEvent.class);
            handleBidPlaced(event);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional
    public void handleBidPlaced(BidPlacedEvent event) {
        // Idempotency: bidId is the PK — if it already exists this is a replay.
        if (bidRepo.existsById(event.bidId())) return;

        Instant bidAt = Instant.parse(event.bidAcceptedAt());
        Instant now   = Instant.now();

        // Append the immutable bid record.
        Bid bid = new Bid();
        bid.setBidId(event.bidId());
        bid.setAuctionId(event.auctionId());
        bid.setBidderId(event.userId());
        bid.setItemId(event.itemId());
        bid.setAmount(event.amount());
        bid.setBidAt(bidAt);
        bidRepo.save(bid);

        // Update the denormalised auction counters. Returns 0 if the auction
        // row does not exist yet (out-of-order: bid arrived before auction:created).
        int updated = auctionRepo.incrementBidCounters(event.auctionId(), event.amount(), now);
        if (updated == 0) {
            // Create a skeletal auction row so the bid is not orphaned.
            // auction:created will fill in the structural fields when it arrives.
            Auction auction = new Auction();
            auction.setAuctionId(event.auctionId());
            auction.setItemId(event.itemId());
            auction.setStatus("OPEN");
            auction.setCurrentHighest(event.amount());
            auction.setBidCount(1);
            auction.setUpdatedAt(now);
            auctionRepo.save(auction);
        }
    }
}
