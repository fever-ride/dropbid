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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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

    @Override protected String stream()        { return "bid_placed"; }
    @Override protected String group()         { return "query-service"; }
    @Override protected String consumerName()  { return "query-bid-consumer-1"; }
    @Override protected int batchSize()        { return 20; }

    /**
     * Batch path (normal consume loop): parse all records up-front, then
     * process the whole batch in a single @Transactional call — one bulk DB
     * read + two saveAll calls instead of N × 3 individual writes.
     */
    @Override
    protected void handleBatch(List<MapRecord<String, Object, Object>> records) {
        List<BidPlacedEvent> events = new ArrayList<>(records.size());
        for (MapRecord<String, Object, Object> record : records) {
            try {
                String json = (String) record.getValue().get("data");
                events.add(mapper.readValue(json, BidPlacedEvent.class));
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse bid_placed record " + record.getId(), e);
            }
        }
        handleBidPlacedBatch(events);
    }

    /**
     * Single-message path: used by the PEL reclaim loop when replaying an
     * individual stuck message.  Delegates to the same batch handler so the
     * idempotency and write logic stays in one place.
     */
    @Override
    protected void handleMessage(MapRecord<String, Object, Object> record) {
        try {
            String json = (String) record.getValue().get("data");
            BidPlacedEvent event = mapper.readValue(json, BidPlacedEvent.class);
            handleBidPlacedBatch(List.of(event));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Core batch handler.  Processes N bid events in a single transaction:
     * <ul>
     *   <li>One {@code findAllById} for all AuctionSummary rows touched by the batch</li>
     *   <li>Lazy, cache-backed reads for BidActivity rows (avoids repeat DB hits
     *       when the same (auction, bidder) pair appears more than once)</li>
     *   <li>One {@code saveAll} for AuctionSummary, one {@code saveAll} for BidActivity</li>
     * </ul>
     *
     * Idempotency: any event whose {@code bidId} equals the stored
     * {@code lastBidId} on its AuctionSummary is silently skipped, guarding
     * against PEL redelivery double-counting.
     */
    @Transactional
    public void handleBidPlacedBatch(List<BidPlacedEvent> events) {
        if (events.isEmpty()) return;
        Instant now = Instant.now();

        // ── 1. Bulk-load all AuctionSummaries this batch touches ──────────────
        Set<String> auctionIds = events.stream()
                .map(BidPlacedEvent::auctionId)
                .collect(Collectors.toSet());
        Map<String, AuctionSummary> summaryMap = new HashMap<>();
        auctionRepo.findAllById(auctionIds)
                .forEach(s -> summaryMap.put(s.getAuctionId(), s));

        // ── 2. In-batch activity cache  (key = auctionId + ":" + bidderId) ───
        //    Avoids duplicate DB reads when the same (auction, bidder) pair
        //    appears more than once within this batch.
        Map<String, BidActivity> activityCache = new HashMap<>();

        // Tracks which AuctionSummary objects were actually mutated so we only
        // saveAll the dirty ones (Hibernate dirty-check handles managed entities,
        // but new/unmanaged summaries still need an explicit collect).
        Set<AuctionSummary> dirtySummaries = new LinkedHashSet<>();

        // Tracks every bidId processed within this batch invocation.
        // lastBidId on AuctionSummary guards against cross-batch PEL redelivery,
        // but if the same auction has multiple events in one batch (B1, B2), after
        // commit lastBidId == B2, so B1 would not be caught on redelivery without
        // this additional in-batch set.
        Set<String> processedInBatch = new HashSet<>();

        // ── 3. Process each event in stream order ─────────────────────────────
        for (BidPlacedEvent event : events) {
            Instant bidTime = Instant.parse(event.bidAcceptedAt());

            AuctionSummary summary = summaryMap.get(event.auctionId());

            // Idempotency guard: skip if already processed this exact bid —
            // either in a previous batch (lastBidId on the persisted summary)
            // or earlier in this same batch invocation (processedInBatch).
            if (processedInBatch.contains(event.bidId())
                    || (summary != null && event.bidId().equals(summary.getLastBidId()))) {
                continue;
            }

            // Create the summary row on the first bid for a brand-new auction.
            if (summary == null) {
                summary = new AuctionSummary();
                summary.setAuctionId(event.auctionId());
                summary.setItemId(event.itemId());
                summary.setStatus("OPEN");
                summaryMap.put(event.auctionId(), summary);
            }
            if (event.sellerId() != null) {
                summary.setSellerId(event.sellerId());
            }
            summary.setCurrentHighest(Math.max(summary.getCurrentHighest(), event.amount()));
            summary.setBidCount(summary.getBidCount() + 1);
            summary.setLastBidId(event.bidId());
            summary.setUpdatedAt(now);
            dirtySummaries.add(summary);
            processedInBatch.add(event.bidId());

            // ── current bidder → ACTIVE ───────────────────────────────────────
            String currentKey = event.auctionId() + ":" + event.userId();
            BidActivity activity = getOrLoadActivity(activityCache, currentKey,
                    event.auctionId(), event.userId());

            if (activity != null) {
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
                activityCache.put(currentKey, activity);
            }
            activity.setUpdatedAt(now);

            // ── previous bidder → OUTBID ──────────────────────────────────────
            if (event.previousBidder() != null && !event.previousBidder().isBlank()
                    && !event.previousBidder().equals(event.userId())) {
                String prevKey = event.auctionId() + ":" + event.previousBidder();
                BidActivity prev = getOrLoadActivity(activityCache, prevKey,
                        event.auctionId(), event.previousBidder());
                if (prev != null) {
                    prev.setBidStatus("OUTBID");
                    prev.setUpdatedAt(now);
                }
            }
        }

        // ── 4. Bulk write ─────────────────────────────────────────────────────
        if (!dirtySummaries.isEmpty()) {
            auctionRepo.saveAll(dirtySummaries);
        }

        List<BidActivity> activitiesToSave = activityCache.values().stream()
                .filter(a -> a != null)
                .collect(Collectors.toList());
        if (!activitiesToSave.isEmpty()) {
            bidRepo.saveAll(activitiesToSave);
        }
    }

    /**
     * Returns the BidActivity for (auctionId, bidderId) from the in-memory cache;
     * on a cache miss it falls back to a DB lookup and stores the result — including
     * {@code null} — so subsequent lookups for the same key skip the DB entirely.
     */
    private BidActivity getOrLoadActivity(Map<String, BidActivity> cache,
                                           String key,
                                           String auctionId,
                                           String bidderId) {
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        BidActivity loaded = bidRepo.findByAuctionIdAndBidderId(auctionId, bidderId).orElse(null);
        cache.put(key, loaded);   // store null to avoid repeated DB misses for the same key
        return loaded;
    }
}
