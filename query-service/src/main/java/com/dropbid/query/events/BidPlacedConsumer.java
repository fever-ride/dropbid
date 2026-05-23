package com.dropbid.query.events;

import com.dropbid.query.model.AuctionSummary;
import com.dropbid.query.model.BidActivity;
import com.dropbid.query.repository.AuctionSummaryRepository;
import com.dropbid.query.repository.BidActivityRepository;
import com.dropbid.shared.events.BidPlacedEvent;
import com.dropbid.shared.streaming.ResilientStreamConsumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Component
public class BidPlacedConsumer extends ResilientStreamConsumer {

    private final AuctionSummaryRepository auctionRepo;
    private final BidActivityRepository bidRepo;
    private final ObjectMapper mapper;

    public BidPlacedConsumer(StringRedisTemplate redis,
                             AuctionSummaryRepository auctionRepo,
                             BidActivityRepository bidRepo,
                             ObjectMapper mapper) {
        super(redis);
        this.auctionRepo = auctionRepo;
        this.bidRepo = bidRepo;
        this.mapper = mapper;
    }

    @Override protected String stream() { return "bid_placed"; }
    @Override protected String group() { return "query-service"; }
    @Override protected String consumerName() { return "query-bid-consumer-1"; }
    @Override protected int batchSize() { return 20; }

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
        Instant now = Instant.now();
        Instant bidTime = Instant.parse(event.bidAcceptedAt());

        AuctionSummary summary = auctionRepo.findById(event.auctionId()).orElseGet(() -> {
            AuctionSummary s = new AuctionSummary();
            s.setAuctionId(event.auctionId());
            s.setItemId(event.itemId());
            s.setStatus("OPEN");
            return s;
        });
        if (event.sellerId() != null) {
            summary.setSellerId(event.sellerId());
        }
        summary.setCurrentHighest(Math.max(summary.getCurrentHighest(), event.amount()));
        summary.setBidCount(summary.getBidCount() + 1);
        summary.setUpdatedAt(now);
        auctionRepo.save(summary);

        Optional<BidActivity> existing = bidRepo.findByAuctionIdAndBidderId(
                event.auctionId(), event.userId());
        BidActivity activity;
        if (existing.isPresent()) {
            activity = existing.get();
            activity.setLatestAmount(event.amount());
            activity.setBidCount(activity.getBidCount() + 1);
            activity.setLastBidAt(bidTime);
            activity.setBidStatus("ACTIVE");
        } else {
            activity = new BidActivity();
            activity.setAuctionId(event.auctionId());
            activity.setItemId(event.itemId());
            activity.setBidderId(event.userId());
            activity.setLatestAmount(event.amount());
            activity.setFirstBidAt(bidTime);
            activity.setLastBidAt(bidTime);
            activity.setBidStatus("ACTIVE");
        }
        activity.setUpdatedAt(now);
        bidRepo.save(activity);

        if (event.previousBidder() != null && !event.previousBidder().isBlank()
                && !event.previousBidder().equals(event.userId())) {
            bidRepo.findByAuctionIdAndBidderId(event.auctionId(), event.previousBidder())
                    .ifPresent(prev -> {
                        prev.setBidStatus("OUTBID");
                        prev.setUpdatedAt(now);
                        bidRepo.save(prev);
                    });
        }
    }
}
