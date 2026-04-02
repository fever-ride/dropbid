package com.dropbid.user.controller;

import com.dropbid.shared.security.UserPrincipal;
import com.dropbid.user.dto.AuthResponse;
import com.dropbid.user.dto.LoginRequest;
import com.dropbid.user.dto.RegisterRequest;
import com.dropbid.user.dto.UserResponse;
import com.dropbid.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/users")
public class UserController {

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    /** POST /users/register */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest req) {
        return service.register(req);
    }

    /** POST /users/login */
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req) {
        return service.login(req);
    }

    /** GET /users/{id} — any authenticated user can fetch profiles */
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public UserResponse getUser(@PathVariable String id) {
        return UserResponse.from(service.getById(id));
    }

    /** GET /users/me — returns the authenticated user's own profile */
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public UserResponse me(@AuthenticationPrincipal UserPrincipal principal) {
        return UserResponse.from(service.getById(principal.userId()));
    }
}
