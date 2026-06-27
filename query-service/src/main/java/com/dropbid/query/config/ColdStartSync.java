package com.dropbid.query.config;

import com.dropbid.query.model.ItemLookup;
import com.dropbid.query.model.UserLookup;
import com.dropbid.query.repository.ItemLookupRepository;
import com.dropbid.query.repository.UserLookupRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

@Component
public class ColdStartSync {

    private static final Logger log = LoggerFactory.getLogger(ColdStartSync.class);

    @Value("${sync.user-service-url:http://localhost:8082}")
    private String userServiceUrl;

    @Value("${sync.shop-service-url:http://localhost:8083}")
    private String shopServiceUrl;

    private final UserLookupRepository userRepo;
    private final ItemLookupRepository itemRepo;
    private final ObjectMapper mapper;
    private final ServiceTokenProvider serviceToken;

    public ColdStartSync(UserLookupRepository userRepo,
                          ItemLookupRepository itemRepo,
                          ObjectMapper mapper,
                          ServiceTokenProvider serviceToken) {
        this.userRepo     = userRepo;
        this.itemRepo     = itemRepo;
        this.mapper       = mapper;
        this.serviceToken = serviceToken;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        Thread.ofVirtual().name("cold-start-sync").start(() -> {
            syncUsers();
            syncItems();
        });
    }

    private void syncUsers() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> resp = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(userServiceUrl + "/internal/users"))
                            .header("Authorization", serviceToken.bearerHeader())
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != 200) {
                log.warn("cold-start user sync failed: status={}", resp.statusCode());
                return;
            }

            JsonNode users = mapper.readTree(resp.body());
            int count = 0;
            for (JsonNode u : users) {
                String userId = u.get("id").asText();
                if (userRepo.existsById(userId)) continue;

                UserLookup lookup = new UserLookup();
                lookup.setUserId(userId);
                lookup.setUsername(u.get("username").asText());
                lookup.setRole(u.get("role").asText());
                lookup.setUpdatedAt(Instant.now());
                userRepo.save(lookup);
                count++;
            }
            log.info("cold-start user sync complete: {} new users added", count);
        } catch (Exception e) {
            log.warn("cold-start user sync failed (service may not be ready): {}", e.getMessage());
        }
    }

    private void syncItems() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> resp = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(shopServiceUrl + "/internal/items"))
                            .header("Authorization", serviceToken.bearerHeader())
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != 200) {
                log.warn("cold-start item sync failed: status={}", resp.statusCode());
                return;
            }

            JsonNode items = mapper.readTree(resp.body());
            int count = 0;
            for (JsonNode i : items) {
                String itemId = i.get("id").asText();
                if (itemRepo.existsById(itemId)) continue;

                ItemLookup lookup = new ItemLookup();
                lookup.setItemId(itemId);
                lookup.setShopId(i.get("shopId").asText());
                lookup.setTitle(i.get("title").asText());
                lookup.setImageUrl(i.has("imageUrl") && !i.get("imageUrl").isNull()
                        ? i.get("imageUrl").asText() : null);
                lookup.setSeries(i.has("series") && !i.get("series").isNull()
                        ? i.get("series").asText() : null);
                lookup.setCondition(i.has("condition") && !i.get("condition").isNull()
                        ? i.get("condition").asText() : null);
                lookup.setUpdatedAt(Instant.now());
                itemRepo.save(lookup);
                count++;
            }
            log.info("cold-start item sync complete: {} new items added", count);
        } catch (Exception e) {
            log.warn("cold-start item sync failed (service may not be ready): {}", e.getMessage());
        }
    }
}
