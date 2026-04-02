package com.dropbid.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record RegisterRequest(
        @Email @NotBlank String email,
        @NotBlank String password,
        @NotBlank String username,
        @Pattern(regexp = "BUYER|SELLER", message = "role must be BUYER or SELLER") String role
) {}
