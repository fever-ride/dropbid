package com.dropbid.shop.controller;

import com.dropbid.shared.security.UserPrincipal;
import com.dropbid.shop.dto.*;
import com.dropbid.shop.service.ShopService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping
public class ShopController {

    private final ShopService service;

    public ShopController(ShopService service) {
        this.service = service;
    }

    /** POST /shops — seller creates their profile */
    @PostMapping("/shops")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SELLER')")
    public ShopResponse createShop(
            @Valid @RequestBody CreateShopRequest req,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ShopResponse.from(service.createShop(principal.userId(), req));
    }

    /** GET /shops/{id} */
    @GetMapping("/shops/{id}")
    @PreAuthorize("isAuthenticated()")
    public ShopResponse getShop(@PathVariable String id) {
        return ShopResponse.from(service.getShop(id));
    }

    /** GET /shops/owner/{ownerId} */
    @GetMapping("/shops/owner/{ownerId}")
    @PreAuthorize("isAuthenticated()")
    public ShopResponse getShopByOwner(@PathVariable String ownerId) {
        return ShopResponse.from(service.getShopByOwner(ownerId));
    }

    /** POST /shops/{shopId}/items — seller lists a collectible */
    @PostMapping("/shops/{shopId}/items")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SELLER')")
    public ItemResponse addItem(
            @PathVariable String shopId,
            @Valid @RequestBody CreateItemRequest req,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ItemResponse.from(service.addItem(shopId, principal.userId(), req));
    }

    /** GET /shops/{shopId}/items */
    @GetMapping("/shops/{shopId}/items")
    @PreAuthorize("isAuthenticated()")
    public List<ItemResponse> listItems(@PathVariable String shopId) {
        return service.listItems(shopId).stream().map(ItemResponse::from).toList();
    }

    /** GET /items/{id} */
    @GetMapping("/items/{id}")
    @PreAuthorize("isAuthenticated()")
    public ItemResponse getItem(@PathVariable String id) {
        return ItemResponse.from(service.getItem(id));
    }
}
