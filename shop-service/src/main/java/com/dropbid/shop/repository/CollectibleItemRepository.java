package com.dropbid.shop.repository;

import com.dropbid.shop.model.CollectibleItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CollectibleItemRepository extends JpaRepository<CollectibleItem, String> {
    List<CollectibleItem> findByShopId(String shopId);
}
