package com.dropbid.query.controller;

import com.dropbid.query.dto.EnrichedAuctionSummary;
import com.dropbid.query.dto.EnrichedBidActivity;
import com.dropbid.query.model.AuctionSummary;
import com.dropbid.query.model.BidActivity;
import com.dropbid.query.model.ItemLookup;
import com.dropbid.query.model.UserLookup;
import com.dropbid.query.repository.ItemLookupRepository;
import com.dropbid.query.repository.UserLookupRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class EnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(EnrichmentService.class);

    @Value("${sync.user-service-url:http://localhost:8082}")
    private String userServiceUrl;

    @Value("${sync.shop-service-url:http://localhost:8083}")
    private String shopServiceUrl;

    private final UserLookupRepository userRepo;
    private final ItemLookupRepository itemRepo;
    private final ObjectMapper mapper;

    public EnrichmentService(UserLookupRepository userRepo,
                              ItemLookupRepository itemRepo,
                              ObjectMapper mapper) {
        this.userRepo = userRepo;
        this.itemRepo = itemRepo;
        this.mapper   = mapper;
    }

    public Page<EnrichedBidActivity> enrichBids(Page<BidActivity> page) {
        Set<String> userIds = page.getContent().stream().map(BidActivity::getBidderId).collect(Collectors.toSet());
        Set<String> itemIds = page.getContent().stream().map(BidActivity::getItemId).collect(Collectors.toSet());

        Map<String, UserLookup> users = resolveUsers(userIds);
        Map<String, ItemLookup> items = resolveItems(itemIds);

        return page.map(ba -> EnrichedBidActivity.from(
                ba, users.get(ba.getBidderId()), items.get(ba.getItemId())));
    }

    public List<EnrichedBidActivity> enrichBidList(List<BidActivity> list) {
        Set<String> userIds = list.stream().map(BidActivity::getBidderId).collect(Collectors.toSet());
        Set<String> itemIds = list.stream().map(BidActivity::getItemId).collect(Collectors.toSet());

        Map<String, UserLookup> users = resolveUsers(userIds);
        Map<String, ItemLookup> items = resolveItems(itemIds);

        return list.stream()
                .map(ba -> EnrichedBidActivity.from(ba, users.get(ba.getBidderId()), items.get(ba.getItemId())))
                .toList();
    }

    public Page<EnrichedAuctionSummary> enrichAuctions(Page<AuctionSummary> page) {
        Set<String> itemIds = page.getContent().stream().map(AuctionSummary::getItemId).collect(Collectors.toSet());
        Map<String, ItemLookup> items = resolveItems(itemIds);
        return page.map(s -> EnrichedAuctionSummary.from(s, items.get(s.getItemId())));
    }

    private Map<String, UserLookup> resolveUsers(Set<String> userIds) {
        Map<String, UserLookup> found = userRepo.findAllById(userIds).stream()
                .collect(Collectors.toMap(UserLookup::getUserId, Function.identity()));

        Set<String> missing = new HashSet<>(userIds);
        missing.removeAll(found.keySet());

        for (String userId : missing) {
            UserLookup fetched = fetchUserFallback(userId);
            if (fetched != null) found.put(userId, fetched);
        }
        return found;
    }

    private Map<String, ItemLookup> resolveItems(Set<String> itemIds) {
        Map<String, ItemLookup> found = itemRepo.findAllById(itemIds).stream()
                .collect(Collectors.toMap(ItemLookup::getItemId, Function.identity()));

        Set<String> missing = new HashSet<>(itemIds);
        missing.removeAll(found.keySet());

        for (String itemId : missing) {
            ItemLookup fetched = fetchItemFallback(itemId);
            if (fetched != null) found.put(itemId, fetched);
        }
        return found;
    }

    private UserLookup fetchUserFallback(String userId) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2)).build();
            HttpResponse<String> resp = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(userServiceUrl + "/internal/users/" + userId))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != 200) return null;

            JsonNode node = mapper.readTree(resp.body());
            UserLookup lookup = new UserLookup();
            lookup.setUserId(userId);
            lookup.setUsername(node.get("username").asText());
            lookup.setRole(node.get("role").asText());
            lookup.setUpdatedAt(Instant.now());
            userRepo.save(lookup);
            log.debug("fallback fetched user {}", userId);
            return lookup;
        } catch (Exception e) {
            log.warn("fallback fetch user {} failed: {}", userId, e.getMessage());
            return null;
        }
    }

    private ItemLookup fetchItemFallback(String itemId) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2)).build();
            HttpResponse<String> resp = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(shopServiceUrl + "/internal/items/" + itemId))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != 200) return null;

            JsonNode node = mapper.readTree(resp.body());
            ItemLookup lookup = new ItemLookup();
            lookup.setItemId(itemId);
            lookup.setShopId(node.get("shopId").asText());
            lookup.setTitle(node.get("title").asText());
            lookup.setImageUrl(node.has("imageUrl") && !node.get("imageUrl").isNull()
                    ? node.get("imageUrl").asText() : null);
            lookup.setSeries(node.has("series") && !node.get("series").isNull()
                    ? node.get("series").asText() : null);
            lookup.setCondition(node.has("condition") && !node.get("condition").isNull()
                    ? node.get("condition").asText() : null);
            lookup.setUpdatedAt(Instant.now());
            itemRepo.save(lookup);
            log.debug("fallback fetched item {}", itemId);
            return lookup;
        } catch (Exception e) {
            log.warn("fallback fetch item {} failed: {}", itemId, e.getMessage());
            return null;
        }
    }
}
