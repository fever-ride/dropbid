package com.dropbid.query.repository;

import com.dropbid.query.model.ItemLookup;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemLookupRepository extends JpaRepository<ItemLookup, String> {
}
