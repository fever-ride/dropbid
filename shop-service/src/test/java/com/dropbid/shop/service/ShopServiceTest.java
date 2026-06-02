package com.dropbid.shop.service;

import com.dropbid.shared.events.ItemUpdatedEvent;
import com.dropbid.shop.dto.CreateItemRequest;
import com.dropbid.shop.dto.CreateShopRequest;
import com.dropbid.shop.events.ItemEventPublisher;
import com.dropbid.shop.model.CollectibleItem;
import com.dropbid.shop.model.Condition;
import com.dropbid.shop.model.SellerProfile;
import com.dropbid.shop.repository.CollectibleItemRepository;
import com.dropbid.shop.repository.SellerProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShopServiceTest {

    @Mock SellerProfileRepository  profileRepo;
    @Mock CollectibleItemRepository itemRepo;
    @Mock ItemEventPublisher        eventPublisher;

    ShopService service;

    @BeforeEach
    void setUp() {
        service = new ShopService(profileRepo, itemRepo, eventPublisher);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    static SellerProfile profile(String id, String ownerId) {
        SellerProfile p = new SellerProfile();
        p.setId(id);
        p.setOwnerId(ownerId);
        p.setName("Test Shop");
        p.setBio("A test seller");
        return p;
    }

    static CollectibleItem item(String id, String shopId) {
        CollectibleItem i = new CollectibleItem();
        i.setId(id);
        i.setShopId(shopId);
        i.setTitle("Pikachu Figure");
        i.setCondition(Condition.NEW);
        i.setOriginalRetailPrice(2000L);
        i.setEstimatedMarketValue(3500L);
        i.setImageUrl("http://img.test/pikachu.jpg");
        return i;
    }

    static CreateShopRequest shopReq(String name, String bio) {
        return new CreateShopRequest(name, bio);
    }

    static CreateItemRequest itemReq() {
        return new CreateItemRequest(
                "Pikachu Figure", "A classic Pikachu", "Gen 1", "First Edition",
                "NEW", 2000L, 3500L, "http://img.test/pikachu.jpg");
    }

    // ── createShop ─────────────────────────────────────────────────────────────

    @Test
    void createShop_noExistingShop_savesAndReturnsProfile() {
        when(profileRepo.existsByOwnerId("seller-1")).thenReturn(false);
        SellerProfile saved = profile("shop-1", "seller-1");
        when(profileRepo.save(any())).thenReturn(saved);

        SellerProfile result = service.createShop("seller-1", shopReq("My Shop", "Bio here"));

        assertThat(result.getId()).isEqualTo("shop-1");
        assertThat(result.getOwnerId()).isEqualTo("seller-1");

        ArgumentCaptor<SellerProfile> cap = ArgumentCaptor.forClass(SellerProfile.class);
        verify(profileRepo).save(cap.capture());
        assertThat(cap.getValue().getOwnerId()).isEqualTo("seller-1");
        assertThat(cap.getValue().getName()).isEqualTo("My Shop");
        assertThat(cap.getValue().getBio()).isEqualTo("Bio here");
    }

    @Test
    void createShop_duplicateOwner_throws409() {
        when(profileRepo.existsByOwnerId("seller-dup")).thenReturn(true);

        assertThatThrownBy(() -> service.createShop("seller-dup", shopReq("Dup Shop", null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409")
                .hasMessageContaining("already has a shop");

        verify(profileRepo, never()).save(any());
    }

    // ── getShop ────────────────────────────────────────────────────────────────

    @Test
    void getShop_found_returnsProfile() {
        when(profileRepo.findById("shop-1")).thenReturn(Optional.of(profile("shop-1", "seller-1")));

        SellerProfile result = service.getShop("shop-1");

        assertThat(result.getId()).isEqualTo("shop-1");
    }

    @Test
    void getShop_notFound_throws404() {
        when(profileRepo.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getShop("missing"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    // ── getShopByOwner ─────────────────────────────────────────────────────────

    @Test
    void getShopByOwner_found_returnsProfile() {
        when(profileRepo.findByOwnerId("seller-1"))
                .thenReturn(Optional.of(profile("shop-1", "seller-1")));

        SellerProfile result = service.getShopByOwner("seller-1");

        assertThat(result.getOwnerId()).isEqualTo("seller-1");
    }

    @Test
    void getShopByOwner_notFound_throws404() {
        when(profileRepo.findByOwnerId("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getShopByOwner("unknown"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    // ── addItem ────────────────────────────────────────────────────────────────

    @Test
    void addItem_ownerMatches_savesItemAndPublishesEvent() {
        SellerProfile shop = profile("shop-1", "seller-1");
        when(profileRepo.findById("shop-1")).thenReturn(Optional.of(shop));
        CollectibleItem saved = item("item-1", "shop-1");
        when(itemRepo.save(any())).thenReturn(saved);

        CollectibleItem result = service.addItem("shop-1", "seller-1", itemReq());

        assertThat(result.getId()).isEqualTo("item-1");
        assertThat(result.getShopId()).isEqualTo("shop-1");

        // item fields set correctly
        ArgumentCaptor<CollectibleItem> itemCap = ArgumentCaptor.forClass(CollectibleItem.class);
        verify(itemRepo).save(itemCap.capture());
        assertThat(itemCap.getValue().getTitle()).isEqualTo("Pikachu Figure");
        assertThat(itemCap.getValue().getCondition()).isEqualTo(Condition.NEW);
        assertThat(itemCap.getValue().getOriginalRetailPrice()).isEqualTo(2000L);
    }

    @Test
    void addItem_publishesItemUpdatedEvent() {
        SellerProfile shop = profile("shop-1", "seller-1");
        when(profileRepo.findById("shop-1")).thenReturn(Optional.of(shop));
        CollectibleItem saved = item("item-1", "shop-1");
        when(itemRepo.save(any())).thenReturn(saved);

        service.addItem("shop-1", "seller-1", itemReq());

        ArgumentCaptor<ItemUpdatedEvent> cap = ArgumentCaptor.forClass(ItemUpdatedEvent.class);
        verify(eventPublisher).publish(cap.capture());
        assertThat(cap.getValue().itemId()).isEqualTo("item-1");
        assertThat(cap.getValue().shopId()).isEqualTo("shop-1");
        assertThat(cap.getValue().title()).isEqualTo("Pikachu Figure");
    }

    @Test
    void addItem_shopNotFound_throws404() {
        when(profileRepo.findById("missing-shop")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addItem("missing-shop", "seller-1", itemReq()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");

        verifyNoInteractions(itemRepo);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void addItem_requestingUserIsNotOwner_throws403() {
        SellerProfile shop = profile("shop-1", "real-owner");
        when(profileRepo.findById("shop-1")).thenReturn(Optional.of(shop));

        assertThatThrownBy(() -> service.addItem("shop-1", "imposter", itemReq()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403")
                .hasMessageContaining("not your shop");

        verifyNoInteractions(itemRepo);
        verifyNoInteractions(eventPublisher);
    }

    // ── listItems ──────────────────────────────────────────────────────────────

    @Test
    void listItems_delegatesToRepository() {
        CollectibleItem i1 = item("item-1", "shop-1");
        CollectibleItem i2 = item("item-2", "shop-1");
        when(itemRepo.findByShopId("shop-1")).thenReturn(List.of(i1, i2));

        List<CollectibleItem> result = service.listItems("shop-1");

        assertThat(result).hasSize(2);
        assertThat(result).extracting(CollectibleItem::getId).containsExactly("item-1", "item-2");
    }

    @Test
    void listItems_noItems_returnsEmptyList() {
        when(itemRepo.findByShopId("empty-shop")).thenReturn(List.of());

        List<CollectibleItem> result = service.listItems("empty-shop");

        assertThat(result).isEmpty();
    }

    // ── getItem ────────────────────────────────────────────────────────────────

    @Test
    void getItem_found_returnsItem() {
        CollectibleItem i = item("item-1", "shop-1");
        when(itemRepo.findById("item-1")).thenReturn(Optional.of(i));

        CollectibleItem result = service.getItem("item-1");

        assertThat(result.getId()).isEqualTo("item-1");
    }

    @Test
    void getItem_notFound_throws404() {
        when(itemRepo.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getItem("missing"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }
}
