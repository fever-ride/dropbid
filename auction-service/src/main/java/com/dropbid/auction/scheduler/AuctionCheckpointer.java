package com.dropbid.auction.scheduler;

import com.dropbid.auction.model.Auction;
import com.dropbid.auction.repository.AuctionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Periodically flushes the live Redis state of all OPEN auctions into DynamoDB.
 *
 * DynamoDB is not the write path — Redis is. This checkpoint exists solely so that
 * if Redis loses data mid-auction, seedRedisCache() can reconstruct the winners set
 * and current bid state from the last snapshot rather than starting from scratch.
 *
 * Acceptable data loss window: up to one checkpoint interval (default 30 s).
 */
@Component
public class AuctionCheckpointer {

    private static final Logger log = LoggerFactory.getLogger(AuctionCheckpointer.class);

    private final AuctionStore        repo;
    private final StringRedisTemplate redis;

    public AuctionCheckpointer(AuctionStore repo, StringRedisTemplate redis) {
        this.repo  = repo;
        this.redis = redis;
    }

    @Scheduled(fixedDelayString = "${auction.checkpoint-interval-ms:30000}")
    public void checkpoint() {
        List<Auction> openAuctions = repo.findByStatus("OPEN");
        int updated = 0;
        for (Auction auction : openAuctions) {
            try {
                flushToDb(auction);
                updated++;
            } catch (ConditionalCheckFailedException e) {
                log.debug("checkpoint skipped for auction {} (stale version)", auction.getAuctionId());
            } catch (Exception e) {
                log.warn("checkpoint failed for auction {}: {}", auction.getAuctionId(), e.getMessage());
            }
        }
        if (updated > 0) {
            log.debug("checkpointed {} open auctions to DynamoDB", updated);
        }
    }

    private void flushToDb(Auction auction) {
        String auctionId  = auction.getAuctionId();
        String hashKey    = "auction:" + auctionId;
        String winnersKey = hashKey + ":winners";

        Map<Object, Object> hash = redis.opsForHash().entries(hashKey);
        if (hash.isEmpty()) return;

        auction.setCurrentHighest(parseLong(hash.get("current_highest")));
        auction.setHighestBidder((String) hash.get("highest_bidder"));
        auction.setBidCount(parseLong(hash.get("bid_count")));
        auction.setVersion(parseLong(hash.get("version")));

        Set<ZSetOperations.TypedTuple<String>> tuples =
                redis.opsForZSet().rangeWithScores(winnersKey, 0, -1);
        if (tuples != null && !tuples.isEmpty()) {
            Map<String, Long> winners = new HashMap<>();
            for (ZSetOperations.TypedTuple<String> t : tuples) {
                if (t.getValue() != null && t.getScore() != null) {
                    winners.put(t.getValue(), t.getScore().longValue());
                }
            }
            auction.setWinners(winners);
        }

        repo.update(auction);
    }

    private static long parseLong(Object val) {
        if (val == null) return 0L;
        try { return Long.parseLong(val.toString()); } catch (NumberFormatException e) { return 0L; }
    }
}
