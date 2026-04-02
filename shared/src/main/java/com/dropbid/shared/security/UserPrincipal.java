package com.dropbid.shared.security;

/**
 * Immutable principal attached to the Spring Security context after JWT validation.
 * Accessible in controllers via {@code @AuthenticationPrincipal UserPrincipal principal}.
 */
public record UserPrincipal(String userId, String role) {}
