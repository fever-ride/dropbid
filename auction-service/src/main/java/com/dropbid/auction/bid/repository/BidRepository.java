package com.dropbid.auction.bid.repository;

import com.dropbid.auction.bid.model.Bid;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.*;
import software.amazon.awssdk.enhanced.dynamodb.model.*;

import java.util.List;

@Repository
public class BidRepository implements BidStore {

    private static final String TABLE_NAME    = "Bids";
    private static final String AUCTION_INDEX = "auction-index";
    private static final String BIDDER_INDEX  = "bidder-index";

    private final DynamoDbTable<Bid> table;

    public BidRepository(DynamoDbEnhancedClient enhanced) {
        this.table = enhanced.table(TABLE_NAME, TableSchema.fromBean(Bid.class));
    }

    public void save(Bid bid) {
        table.putItem(bid);
    }

    public Bid findById(String bidId) {
        return table.getItem(Key.builder().partitionValue(bidId).build());
    }

    public List<Bid> findByAuctionId(String auctionId) {
        DynamoDbIndex<Bid> index = table.index(AUCTION_INDEX);
        QueryConditional condition = QueryConditional.keyEqualTo(
                Key.builder().partitionValue(auctionId).build());
        return index.query(QueryEnhancedRequest.builder()
                        .queryConditional(condition)
                        .scanIndexForward(false) // newest first
                        .build())
                .stream()
                .flatMap(page -> page.items().stream())
                .toList();
    }

    public List<Bid> findByBidderId(String bidderId) {
        DynamoDbIndex<Bid> index = table.index(BIDDER_INDEX);
        QueryConditional condition = QueryConditional.keyEqualTo(
                Key.builder().partitionValue(bidderId).build());
        return index.query(QueryEnhancedRequest.builder()
                        .queryConditional(condition)
                        .scanIndexForward(false)
                        .build())
                .stream()
                .flatMap(page -> page.items().stream())
                .toList();
    }

}
