package com.dropbid.user.dto;

import com.dropbid.user.model.User;

import java.time.Instant;

public record UserResponse(
        String id,
        String email,
        String username,
        String role,
        Instant createdAt
) {
    public static UserResponse from(User u) {
        return new UserResponse(u.getId(), u.getEmail(), u.getUsername(),
                u.getRole().name(), u.getCreatedAt());
    }
}
