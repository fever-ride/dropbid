package com.dropbid.query.events;

import com.dropbid.query.model.Auction;
import com.dropbid.query.model.AuctionWinner;
import com.dropbid.query.repository.AuctionRepository;
import com.dropbid.query.repository.AuctionWinnerRepository;
import com.dropbid.shared.events.AuctionClosedEvent;
import com.dropbid.shared.streaming.ResilientStreamConsumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Consumes {@code auction:closed} events.
 *
 * <p>Two writes per event:
 * <ol>
 *   <li>Mark the {@code auction} row as {@code CLOSED} and record {@code closedAt}.</li>
 *   <li>Insert one {@code auction_winner} row per winner.  The unique constraint on
 *       {@code (auctionId, bidderId)} provides idempotency — a duplicate insert on
 *       redelivery is caught and silently ignored.</li>
 * </ol>
 *
 * <p>No-bid auctions (empty winners map) are handled gracefully: the auction is
 * still marked CLOSED and no winner rows are written.
 */
@Component
public class AuctionClosedConsumer extends ResilientStreamConsumer {

    private final AuctionRepository       auctionRepo;
    private final AuctionWinnerRepository winnerRepo;
    private final ObjectMapper            mapper;

    public AuctionClosedConsumer(StringRedisTemplate redis,
                                  AuctionRepository auctionRepo,
                                  AuctionWinnerRepository winnerRepo,
                                  ObjectMapper mapper) {
        super(redis);
        this.auctionRepo = auctionRepo;
        this.winnerRepo  = winnerRepo;
        this.mapper      = mapper;
    }

    @Override protected String stream()       { return "auction:closed"; }
    @Override protected String group()        { return "query-service"; }
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
        Instant now      = Instant.now();
        Instant closedAt = Instant.parse(event.closedAt());

        // ── 1. Update the auction row (create defensively on out-of-order) ──
        Auction auction = auctionRepo.findById(event.auctionId()).orElseGet(() -> {
            Auction a = new Auction();
            a.setAuctionId(event.auctionId());
            a.setItemId(event.itemId());
            a.setShopId(event.shopId());
            return a;
        });
        auction.setStatus("CLOSED");
        auction.setClosedAt(closedAt);
        auction.setUpdatedAt(now);
        auctionRepo.save(auction);

        // ── 2. Persist winner rows ────────────────────────────────────────────
        if (event.winners() == null || event.winners().isEmpty()) return;

        for (Map.Entry<String, Long> entry : event.winners().entrySet()) {
            String bidderId     = entry.getKey();
            long   winningAmount = entry.getValue();

            // Skip if already written (idempotency on PEL redelivery).
            if (winnerRepo.existsByAuctionIdAndBidderId(event.auctionId(), bidderId)) continue;

            try {
                AuctionWinner winner = new AuctionWinner();
                winner.setAuctionId(event.auctionId());
                winner.setBidderId(bidderId);
                winner.setAmount(winningAmount);
                winner.setUpdatedAt(now);
                winnerRepo.save(winner);
            } catch (DataIntegrityViolationException ignored) {
                // Race between the existsBy check and the save on concurrent replay.
                // Safe to ignore: the row is already there.
            }
        }
    }
}
