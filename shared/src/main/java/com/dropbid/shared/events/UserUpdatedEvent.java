package com.dropbid.shared.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UserUpdatedEvent(
        String userId,
        String username,
        String role,
        String timestamp
) {}
