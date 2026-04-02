package com.dropbid.shop.repository;

import com.dropbid.shop.model.SellerProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SellerProfileRepository extends JpaRepository<SellerProfile, String> {
    Optional<SellerProfile> findByOwnerId(String ownerId);
    boolean existsByOwnerId(String ownerId);
    List<SellerProfile> findAllByOwnerId(String ownerId);
}
