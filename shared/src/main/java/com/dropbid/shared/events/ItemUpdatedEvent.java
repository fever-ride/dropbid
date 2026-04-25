package com.dropbid.shared.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ItemUpdatedEvent(
        String itemId,
        String shopId,
        String title,
        String imageUrl,
        String series,
        String condition,
        String timestamp
) {}
