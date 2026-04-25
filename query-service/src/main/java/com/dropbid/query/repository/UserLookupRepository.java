package com.dropbid.query.repository;

import com.dropbid.query.model.UserLookup;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserLookupRepository extends JpaRepository<UserLookup, String> {
}
