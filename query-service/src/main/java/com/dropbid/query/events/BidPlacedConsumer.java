package com.dropbid.query.events;

import com.dropbid.query.model.AuctionSummary;
import com.dropbid.query.model.BidActivity;
import com.dropbid.query.repository.AuctionSummaryRepository;
import com.dropbid.query.repository.BidActivityRepository;
import com.dropbid.shared.events.BidPlacedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class BidPlacedConsumer {

    private static final Logger log = LoggerFactory.getLogger(BidPlacedConsumer.class);

    private static final String STREAM        = "bid_placed";
    private static final String GROUP         = "query-service";
    private static final String CONSUMER_NAME = "query-bid-consumer-1";
    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(2);

    private final StringRedisTemplate       redis;
    private final AuctionSummaryRepository  auctionRepo;
    private final BidActivityRepository     bidRepo;
    private final ObjectMapper              mapper;

    public BidPlacedConsumer(StringRedisTemplate redis,
                             AuctionSummaryRepository auctionRepo,
                             BidActivityRepository bidRepo,
                             ObjectMapper mapper) {
        this.redis       = redis;
        this.auctionRepo = auctionRepo;
        this.bidRepo     = bidRepo;
        this.mapper      = mapper;
    }

    @PostConstruct
    void start() {
        ensureConsumerGroup();
        Thread.ofVirtual().name("query-bid-consumer").start(this::consumeLoop);
    }

    private void consumeLoop() {
        log.info("Query bid consumer started on stream={} group={}", STREAM, GROUP);
        while (!Thread.currentThread().isInterrupted()) {
            try {
                List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                        Consumer.from(GROUP, CONSUMER_NAME),
                        StreamReadOptions.empty().count(20).block(BLOCK_TIMEOUT),
                        StreamOffset.create(STREAM, ReadOffset.lastConsumed())
                );
                if (records == null || records.isEmpty()) continue;

                for (MapRecord<String, Object, Object> record : records) {
                    processRecord(record);
                }
            } catch (Exception e) {
                log.error("Error in query bid consumer: {}", e.getMessage(), e);
                try { Thread.sleep(1000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void processRecord(MapRecord<String, Object, Object> record) {
        try {
            String json = (String) record.getValue().get("data");
            BidPlacedEvent event = mapper.readValue(json, BidPlacedEvent.class);
            handleBidPlaced(event);
            redis.opsForStream().acknowledge(STREAM, GROUP, record.getId());
        } catch (Exception e) {
            log.error("Failed to process bid_placed record {}: {}", record.getId(), e.getMessage(), e);
        }
    }

    @Transactional
    public void handleBidPlaced(BidPlacedEvent event) {
        Instant now = Instant.now();
        Instant bidTime = Instant.parse(event.bidAcceptedAt());

        // Upsert auction summary
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

        // Upsert bid activity for current bidder
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

        // Mark previous bidder as OUTBID if they were evicted
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

    private void ensureConsumerGroup() {
        try {
            redis.opsForStream().createGroup(STREAM, ReadOffset.from("0"), GROUP);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                log.debug("Consumer group {} already exists for {}", GROUP, STREAM);
            } else {
                log.warn("Could not create consumer group {} for {}: {}", GROUP, STREAM, e.getMessage());
            }
        }
    }
}
