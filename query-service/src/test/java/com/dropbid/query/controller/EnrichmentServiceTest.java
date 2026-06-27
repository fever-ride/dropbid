package com.dropbid.query.controller;

import com.dropbid.query.config.ServiceTokenProvider;
import com.dropbid.query.dto.BidSummaryProjection;
import com.dropbid.query.dto.EnrichedAuctionSummary;
import com.dropbid.query.dto.EnrichedBidActivity;
import com.dropbid.query.model.Auction;
import com.dropbid.query.model.ItemLookup;
import com.dropbid.query.model.UserLookup;
import com.dropbid.query.repository.ItemLookupRepository;
import com.dropbid.query.repository.UserLookupRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EnrichmentService}.
 *
 * <p>The service is constructed directly (no Spring context) so {@code @Value}-injected
 * {@code userServiceUrl} / {@code shopServiceUrl} remain {@code null}.  The HTTP-fallback
 * methods then try {@code URI.create("null/internal/…")} which is a relative URI;
 * {@link java.net.http.HttpClient} rejects it with {@link IllegalArgumentException},
 * the catch-all swallows it and returns {@code null} — exactly the "unreachable host"
 * behaviour we want to exercise without a real network or WireMock.
 */
@ExtendWith(MockitoExtension.class)
class EnrichmentServiceTest {

    @Mock UserLookupRepository  userRepo;
    @Mock ItemLookupRepository  itemRepo;
    @Mock ServiceTokenProvider  serviceToken;

    EnrichmentService service;

    @BeforeEach
    void setUp() {
        service = new EnrichmentService(userRepo, itemRepo, new ObjectMapper(), serviceToken);
    }

    // ── factory helpers ───────────────────────────────────────────────────────

    static Auction auction(String auctionId, String itemId) {
        Auction a = new Auction();
        a.setAuctionId(auctionId);
        a.setItemId(itemId);
        a.setShopId("shop-1");
        a.setSellerId("seller-1");
        a.setStatus("OPEN");
        a.setStartingBid(100L);
        a.setCurrentHighest(200L);
        a.setBidCount(3L);
        a.setEndTime("2025-12-31T00:00:00Z");
        a.setUpdatedAt(Instant.now());
        return a;
    }

    static ItemLookup item(String itemId, String title) {
        ItemLookup i = new ItemLookup();
        i.setItemId(itemId);
        i.setShopId("shop-1");
        i.setTitle(title);
        i.setImageUrl("http://img.test/" + itemId + ".jpg");
        i.setSeries("Series A");
        i.setUpdatedAt(Instant.now());
        return i;
    }

    static UserLookup user(String userId, String username) {
        UserLookup u = new UserLookup();
        u.setUserId(userId);
        u.setUsername(username);
        u.setRole("BUYER");
        u.setUpdatedAt(Instant.now());
        return u;
    }

    static BidSummaryProjection projection(String auctionId, String bidderId, String itemId) {
        BidSummaryProjection p = mock(BidSummaryProjection.class);
        when(p.getAuctionId()).thenReturn(auctionId);
        when(p.getBidderId()).thenReturn(bidderId);
        when(p.getItemId()).thenReturn(itemId);
        when(p.getLatestAmount()).thenReturn(300L);
        when(p.getBidCount()).thenReturn(2L);
        when(p.getBidStatus()).thenReturn("ACTIVE");
        when(p.getPaymentStatus()).thenReturn(null);
        when(p.getPaymentId()).thenReturn(null);
        when(p.getFirstBidAt()).thenReturn(Instant.now());
        when(p.getLastBidAt()).thenReturn(Instant.now());
        return p;
    }

    // ── enrichAuctions ────────────────────────────────────────────────────────

    @Test
    void enrichAuctions_itemFound_populatesItemFields() {
        Auction a = auction("a-1", "item-1");
        ItemLookup i = item("item-1", "Pikachu Figure");
        when(itemRepo.findAllById(any())).thenReturn(List.of(i));

        Page<EnrichedAuctionSummary> result =
                service.enrichAuctions(new PageImpl<>(List.of(a)));

        assertThat(result.getTotalElements()).isEqualTo(1);
        EnrichedAuctionSummary s = result.getContent().get(0);
        assertThat(s.auctionId()).isEqualTo("a-1");
        assertThat(s.itemId()).isEqualTo("item-1");
        assertThat(s.itemTitle()).isEqualTo("Pikachu Figure");
        assertThat(s.itemImageUrl()).isEqualTo("http://img.test/item-1.jpg");
        assertThat(s.itemSeries()).isEqualTo("Series A");
        assertThat(s.sellerId()).isEqualTo("seller-1");
        assertThat(s.bidCount()).isEqualTo(3L);
    }

    @Test
    void enrichAuctions_itemMissingInRepo_nullItemFieldsAndNoException() {
        // When the repo returns nothing and the HTTP fallback silently fails (no
        // real server), EnrichedAuctionSummary.from(a, null) should produce nulls.
        Auction a = auction("a-2", "item-ghost");
        when(itemRepo.findAllById(any())).thenReturn(List.of());

        Page<EnrichedAuctionSummary> result =
                service.enrichAuctions(new PageImpl<>(List.of(a)));

        EnrichedAuctionSummary s = result.getContent().get(0);
        assertThat(s.auctionId()).isEqualTo("a-2");
        assertThat(s.itemTitle()).isNull();
        assertThat(s.itemImageUrl()).isNull();
        assertThat(s.itemSeries()).isNull();
        // Non-item fields are still populated from the Auction entity
        assertThat(s.sellerId()).isEqualTo("seller-1");
        assertThat(s.status()).isEqualTo("OPEN");
    }

    @Test
    void enrichAuctions_emptyPage_returnsEmptyPage() {
        Page<EnrichedAuctionSummary> result =
                service.enrichAuctions(new PageImpl<>(List.of()));

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void enrichAuctions_multipleAuctions_eachMappedToCorrectItem() {
        Auction a1 = auction("a-1", "item-1");
        Auction a2 = auction("a-2", "item-2");
        ItemLookup i1 = item("item-1", "Pikachu");
        ItemLookup i2 = item("item-2", "Charizard");
        when(itemRepo.findAllById(any())).thenReturn(List.of(i1, i2));

        Page<EnrichedAuctionSummary> result =
                service.enrichAuctions(new PageImpl<>(List.of(a1, a2)));

        assertThat(result.getTotalElements()).isEqualTo(2);
        Map<String, EnrichedAuctionSummary> byId = result.getContent().stream()
                .collect(Collectors.toMap(EnrichedAuctionSummary::auctionId, e -> e));
        assertThat(byId.get("a-1").itemTitle()).isEqualTo("Pikachu");
        assertThat(byId.get("a-2").itemTitle()).isEqualTo("Charizard");
    }

    // ── enrichBids ────────────────────────────────────────────────────────────

    @Test
    void enrichBids_allFound_populatesUserAndItemFields() {
        BidSummaryProjection p = projection("a-1", "user-1", "item-1");
        when(userRepo.findAllById(any())).thenReturn(List.of(user("user-1", "alice")));
        when(itemRepo.findAllById(any())).thenReturn(List.of(item("item-1", "Snorlax")));

        Page<EnrichedBidActivity> result =
                service.enrichBids(new PageImpl<>(List.of(p)));

        assertThat(result.getTotalElements()).isEqualTo(1);
        EnrichedBidActivity b = result.getContent().get(0);
        assertThat(b.auctionId()).isEqualTo("a-1");
        assertThat(b.bidderId()).isEqualTo("user-1");
        assertThat(b.bidderName()).isEqualTo("alice");
        assertThat(b.itemTitle()).isEqualTo("Snorlax");
        assertThat(b.latestAmount()).isEqualTo(300L);
        assertThat(b.bidStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void enrichBids_userMissing_nullBidderName() {
        BidSummaryProjection p = projection("a-1", "user-ghost", "item-1");
        when(userRepo.findAllById(any())).thenReturn(List.of()); // user not found
        when(itemRepo.findAllById(any())).thenReturn(List.of(item("item-1", "Mewtwo")));

        Page<EnrichedBidActivity> result =
                service.enrichBids(new PageImpl<>(List.of(p)));

        EnrichedBidActivity b = result.getContent().get(0);
        assertThat(b.bidderName()).isNull();
        assertThat(b.itemTitle()).isEqualTo("Mewtwo");
    }

    @Test
    void enrichBids_itemMissing_nullItemTitle() {
        BidSummaryProjection p = projection("a-1", "user-1", "item-ghost");
        when(userRepo.findAllById(any())).thenReturn(List.of(user("user-1", "bob")));
        when(itemRepo.findAllById(any())).thenReturn(List.of()); // item not found

        Page<EnrichedBidActivity> result =
                service.enrichBids(new PageImpl<>(List.of(p)));

        EnrichedBidActivity b = result.getContent().get(0);
        assertThat(b.itemTitle()).isNull();
        assertThat(b.itemImageUrl()).isNull();
        assertThat(b.bidderName()).isEqualTo("bob");
    }

    @Test
    void enrichBids_emptyPage_returnsEmptyPage() {
        Page<EnrichedBidActivity> result =
                service.enrichBids(new PageImpl<>(List.of()));

        assertThat(result.isEmpty()).isTrue();
    }

    // ── enrichBidList ─────────────────────────────────────────────────────────

    @Test
    void enrichBidList_allFound_returnsList() {
        BidSummaryProjection p = projection("a-1", "user-1", "item-1");
        when(userRepo.findAllById(any())).thenReturn(List.of(user("user-1", "charlie")));
        when(itemRepo.findAllById(any())).thenReturn(List.of(item("item-1", "Eevee")));

        List<EnrichedBidActivity> result = service.enrichBidList(List.of(p));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).bidderName()).isEqualTo("charlie");
        assertThat(result.get(0).itemTitle()).isEqualTo("Eevee");
    }

    @Test
    void enrichBidList_emptyList_returnsEmptyList() {
        List<EnrichedBidActivity> result = service.enrichBidList(List.of());
        assertThat(result).isEmpty();
    }

    @Test
    void enrichBidList_multipleProjections_eachEnrichedWithCorrectLookups() {
        BidSummaryProjection p1 = projection("a-1", "user-1", "item-1");
        BidSummaryProjection p2 = projection("a-2", "user-2", "item-2");
        when(userRepo.findAllById(any()))
                .thenReturn(List.of(user("user-1", "alice"), user("user-2", "bob")));
        when(itemRepo.findAllById(any()))
                .thenReturn(List.of(item("item-1", "Bulbasaur"), item("item-2", "Squirtle")));

        List<EnrichedBidActivity> result = service.enrichBidList(List.of(p1, p2));

        assertThat(result).hasSize(2);
        Map<String, EnrichedBidActivity> byAuction = result.stream()
                .collect(Collectors.toMap(EnrichedBidActivity::auctionId, e -> e));
        assertThat(byAuction.get("a-1").bidderName()).isEqualTo("alice");
        assertThat(byAuction.get("a-1").itemTitle()).isEqualTo("Bulbasaur");
        assertThat(byAuction.get("a-2").bidderName()).isEqualTo("bob");
        assertThat(byAuction.get("a-2").itemTitle()).isEqualTo("Squirtle");
    }

    @Test
    void enrichBidList_partialCacheHit_missingLookupBecomesNull() {
        // user-1 is found; user-2 is not (fallback silently returns null)
        BidSummaryProjection p1 = projection("a-1", "user-1", "item-1");
        BidSummaryProjection p2 = projection("a-2", "user-2", "item-1");
        when(userRepo.findAllById(any()))
                .thenReturn(List.of(user("user-1", "alice"))); // user-2 missing
        when(itemRepo.findAllById(any()))
                .thenReturn(List.of(item("item-1", "Jolteon")));

        List<EnrichedBidActivity> result = service.enrichBidList(List.of(p1, p2));

        Map<String, EnrichedBidActivity> byAuction = result.stream()
                .collect(Collectors.toMap(EnrichedBidActivity::auctionId, e -> e));
        assertThat(byAuction.get("a-1").bidderName()).isEqualTo("alice");
        assertThat(byAuction.get("a-2").bidderName()).isNull(); // fallback failed silently
        // Both projections still got item data from the shared itemId
        assertThat(byAuction.get("a-1").itemTitle()).isEqualTo("Jolteon");
        assertThat(byAuction.get("a-2").itemTitle()).isEqualTo("Jolteon");
    }
}
