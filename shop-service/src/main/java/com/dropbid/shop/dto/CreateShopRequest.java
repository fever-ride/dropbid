package com.dropbid.shop.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateShopRequest(
        @NotBlank String name,
        String bio
) {}
