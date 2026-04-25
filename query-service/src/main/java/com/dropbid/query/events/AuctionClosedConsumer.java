package com.dropbid.query.events;

import com.dropbid.query.model.AuctionSummary;
import com.dropbid.query.model.BidActivity;
import com.dropbid.query.repository.AuctionSummaryRepository;
import com.dropbid.query.repository.BidActivityRepository;
import com.dropbid.shared.events.AuctionClosedEvent;
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
import java.util.ArrayList;
import java.util.List;

@Component
public class AuctionClosedConsumer {

    private static final Logger log = LoggerFactory.getLogger(AuctionClosedConsumer.class);

    private static final String STREAM        = "auction:closed";
    private static final String GROUP         = "query-service";
    private static final String CONSUMER_NAME = "query-closed-consumer-1";
    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(2);

    private final StringRedisTemplate       redis;
    private final AuctionSummaryRepository  auctionRepo;
    private final BidActivityRepository     bidRepo;
    private final ObjectMapper              mapper;

    public AuctionClosedConsumer(StringRedisTemplate redis,
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
        Thread.ofVirtual().name("query-closed-consumer").start(this::consumeLoop);
    }

    private void consumeLoop() {
        log.info("Query auction-closed consumer started on stream={} group={}", STREAM, GROUP);
        while (!Thread.currentThread().isInterrupted()) {
            try {
                List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                        Consumer.from(GROUP, CONSUMER_NAME),
                        StreamReadOptions.empty().count(10).block(BLOCK_TIMEOUT),
                        StreamOffset.create(STREAM, ReadOffset.lastConsumed())
                );
                if (records == null || records.isEmpty()) continue;

                for (MapRecord<String, Object, Object> record : records) {
                    processRecord(record);
                }
            } catch (Exception e) {
                log.error("Error in query auction-closed consumer: {}", e.getMessage(), e);
                try { Thread.sleep(1000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void processRecord(MapRecord<String, Object, Object> record) {
        try {
            String json = (String) record.getValue().get("data");
            AuctionClosedEvent event = mapper.readValue(json, AuctionClosedEvent.class);
            handleAuctionClosed(event);
            redis.opsForStream().acknowledge(STREAM, GROUP, record.getId());
        } catch (Exception e) {
            log.error("Failed to process auction:closed record {}: {}", record.getId(), e.getMessage(), e);
        }
    }

    @Transactional
    public void handleAuctionClosed(AuctionClosedEvent event) {
        Instant now = Instant.now();
        Instant closedAt = Instant.parse(event.closedAt());

        // Update auction summary
        AuctionSummary summary = auctionRepo.findById(event.auctionId()).orElseGet(() -> {
            AuctionSummary s = new AuctionSummary();
            s.setAuctionId(event.auctionId());
            s.setItemId(event.itemId());
            return s;
        });
        summary.setShopId(event.shopId());
        summary.setStatus("CLOSED");
        summary.setClosedAt(closedAt);
        summary.setUpdatedAt(now);
        auctionRepo.save(summary);

        // Mark winners
        List<String> winnerIds = new ArrayList<>(event.winners().keySet());
        List<BidActivity> winnerActivities = bidRepo.findByAuctionIdAndBidderIdIn(
                event.auctionId(), winnerIds);
        for (BidActivity ba : winnerActivities) {
            ba.setBidStatus("WON");
            ba.setUpdatedAt(now);
        }
        bidRepo.saveAll(winnerActivities);

        // Mark non-winners as OUTBID
        List<BidActivity> allBids = bidRepo.findByAuctionId(event.auctionId());
        for (BidActivity ba : allBids) {
            if (!"WON".equals(ba.getBidStatus())) {
                ba.setBidStatus("OUTBID");
                ba.setUpdatedAt(now);
            }
        }
        bidRepo.saveAll(allBids);
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
