package com.dropbid.auction.repository;

import com.dropbid.auction.model.Auction;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.enhanced.dynamodb.*;
import software.amazon.awssdk.enhanced.dynamodb.model.*;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.List;
import java.util.Map;

@Repository
public class AuctionRepository {

    private static final String TABLE_NAME = "Auctions";
    private static final String STATUS_INDEX = "status-index";

    private final DynamoDbTable<Auction> table;

    public AuctionRepository(DynamoDbEnhancedClient enhanced) {
        this.table = enhanced.table(TABLE_NAME, TableSchema.fromBean(Auction.class));
    }

    public void save(Auction auction) {
        table.putItem(auction);
    }

    public Auction findById(String auctionId) {
        Auction result = table.getItem(Key.builder().partitionValue(auctionId).build());
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "auction not found: " + auctionId);
        }
        return result;
    }

    public Auction findByIdOrNull(String auctionId) {
        return table.getItem(Key.builder().partitionValue(auctionId).build());
    }

    /**
     * Query the status-index GSI to find all auctions in a given status.
     * DynamoDB Local requires the GSI to exist (created by init-dynamo.sh).
     */
    public List<Auction> findByStatus(String status) {
        DynamoDbIndex<Auction> index = table.index(STATUS_INDEX);
        QueryConditional condition = QueryConditional.keyEqualTo(
                Key.builder().partitionValue(status).build());
        return index.query(condition).stream()
                .flatMap(page -> page.items().stream())
                .toList();
    }

    public void update(Auction auction) {
        table.updateItem(UpdateItemEnhancedRequest.builder(Auction.class)
                .item(auction)
                .ignoreNulls(true)
                .build());
    }
}
