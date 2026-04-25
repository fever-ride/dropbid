package com.dropbid.shop.controller;

import com.dropbid.shop.dto.ItemResponse;
import com.dropbid.shop.repository.CollectibleItemRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/internal")
public class InternalShopController {

    private final CollectibleItemRepository repo;

    public InternalShopController(CollectibleItemRepository repo) {
        this.repo = repo;
    }

    @GetMapping("/items")
    public List<ItemResponse> allItems() {
        return repo.findAll().stream().map(ItemResponse::from).toList();
    }

    @GetMapping("/items/{id}")
    public ItemResponse getItem(@PathVariable String id) {
        return repo.findById(id)
                .map(ItemResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
