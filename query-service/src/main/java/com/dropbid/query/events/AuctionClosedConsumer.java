package com.dropbid.query.events;

import com.dropbid.query.model.AuctionSummary;
import com.dropbid.query.model.BidActivity;
import com.dropbid.query.repository.AuctionSummaryRepository;
import com.dropbid.query.repository.BidActivityRepository;
import com.dropbid.shared.events.AuctionClosedEvent;
import com.dropbid.shared.streaming.ResilientStreamConsumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class AuctionClosedConsumer extends ResilientStreamConsumer {

    private final AuctionSummaryRepository auctionRepo;
    private final BidActivityRepository bidRepo;
    private final ObjectMapper mapper;

    public AuctionClosedConsumer(StringRedisTemplate redis,
                                  AuctionSummaryRepository auctionRepo,
                                  BidActivityRepository bidRepo,
                                  ObjectMapper mapper) {
        super(redis);
        this.auctionRepo = auctionRepo;
        this.bidRepo = bidRepo;
        this.mapper = mapper;
    }

    @Override protected String stream() { return "auction:closed"; }
    @Override protected String group() { return "query-service"; }
    @Override protected String consumerName() { return "query-closed-consumer-1"; }

    @Override
    protected void handleMessage(MapRecord<String, Object, Object> record) {
        try {
            String json = (String) record.getValue().get("data");
            AuctionClosedEvent event = mapper.readValue(json, AuctionClosedEvent.class);
            handleAuctionClosed(event);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional
    public void handleAuctionClosed(AuctionClosedEvent event) {
        Instant now = Instant.now();
        Instant closedAt = Instant.parse(event.closedAt());

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

        List<String> winnerIds = new ArrayList<>(event.winners().keySet());
        List<BidActivity> winnerActivities = bidRepo.findByAuctionIdAndBidderIdIn(
                event.auctionId(), winnerIds);
        for (BidActivity ba : winnerActivities) {
            ba.setBidStatus("WON");
            ba.setUpdatedAt(now);
        }
        bidRepo.saveAll(winnerActivities);

        List<BidActivity> allBids = bidRepo.findByAuctionId(event.auctionId());
        for (BidActivity ba : allBids) {
            if (!"WON".equals(ba.getBidStatus())) {
                ba.setBidStatus("OUTBID");
                ba.setUpdatedAt(now);
            }
        }
        bidRepo.saveAll(allBids);
    }
}
