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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@Service
public class ShopService {

    private final SellerProfileRepository profileRepo;
    private final CollectibleItemRepository itemRepo;
    private final ItemEventPublisher eventPublisher;

    public ShopService(SellerProfileRepository profileRepo,
                       CollectibleItemRepository itemRepo,
                       ItemEventPublisher eventPublisher) {
        this.profileRepo    = profileRepo;
        this.itemRepo       = itemRepo;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public SellerProfile createShop(String ownerId, CreateShopRequest req) {
        if (profileRepo.existsByOwnerId(ownerId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "seller already has a shop");
        }
        SellerProfile profile = new SellerProfile();
        profile.setOwnerId(ownerId);
        profile.setName(req.name());
        profile.setBio(req.bio());
        return profileRepo.save(profile);
    }

    public SellerProfile getShop(String shopId) {
        return profileRepo.findById(shopId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "shop not found"));
    }

    public SellerProfile getShopByOwner(String ownerId) {
        return profileRepo.findByOwnerId(ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "shop not found"));
    }

    @Transactional
    public CollectibleItem addItem(String shopId, String requestingUserId, CreateItemRequest req) {
        SellerProfile shop = getShop(shopId);
        if (!shop.getOwnerId().equals(requestingUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not your shop");
        }
        CollectibleItem item = new CollectibleItem();
        item.setShopId(shopId);
        item.setTitle(req.title());
        item.setDescription(req.description());
        item.setSeries(req.series());
        item.setEdition(req.edition());
        item.setCondition(Condition.valueOf(req.condition()));
        item.setOriginalRetailPrice(req.originalRetailPrice());
        item.setEstimatedMarketValue(req.estimatedMarketValue());
        item.setImageUrl(req.imageUrl());
        CollectibleItem saved = itemRepo.save(item);

        eventPublisher.publish(new ItemUpdatedEvent(
                saved.getId(), shopId, saved.getTitle(), saved.getImageUrl(),
                saved.getSeries(), saved.getCondition().name(),
                Instant.now().toString()));

        return saved;
    }

    public List<CollectibleItem> listItems(String shopId) {
        return itemRepo.findByShopId(shopId);
    }

    public CollectibleItem getItem(String itemId) {
        return itemRepo.findById(itemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "item not found"));
    }
}
