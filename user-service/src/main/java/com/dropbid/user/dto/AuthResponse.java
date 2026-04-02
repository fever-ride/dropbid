package com.dropbid.user.dto;

public record AuthResponse(String token, String userId, String role) {}
