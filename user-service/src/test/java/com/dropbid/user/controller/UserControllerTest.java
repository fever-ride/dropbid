package com.dropbid.user.controller;

import com.dropbid.shared.security.JwtUtil;
import com.dropbid.user.config.SecurityConfig;
import com.dropbid.user.dto.AuthResponse;
import com.dropbid.user.dto.LoginRequest;
import com.dropbid.user.dto.RegisterRequest;
import com.dropbid.user.model.Role;
import com.dropbid.user.model.User;
import com.dropbid.user.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Slice test for {@link UserController}.
 *
 * <p>Authentication is driven through the real {@link com.dropbid.shared.security.JwtAuthFilter}
 * by mocking {@link JwtUtil#validateToken} to return pre-built {@link Claims} objects.
 * Public endpoints ({@code /users/register}, {@code /users/login}) are verified to work
 * without any {@code Authorization} header.
 */
@WebMvcTest(UserController.class)
@Import(SecurityConfig.class)
class UserControllerTest {

    @Autowired MockMvc      mockMvc;
    @Autowired ObjectMapper mapper;

    @MockBean UserService service;
    @MockBean JwtUtil     jwtUtil;

    @BeforeEach
    void setUp() {
        // "user-token" → principal user-1 / role BUYER
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("user-1");
        when(claims.get("role", String.class)).thenReturn("BUYER");
        when(jwtUtil.validateToken("user-token")).thenReturn(claims);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    static User user(String id, String email, String username) {
        User u = new User();
        u.setId(id);
        u.setEmail(email);
        u.setUsername(username);
        u.setRole(Role.BUYER);
        u.setCreatedAt(Instant.parse("2024-01-01T00:00:00Z"));
        return u;
    }

    // ── POST /users/register ──────────────────────────────────────────────────

    @Test
    void register_validRequest_returns201WithAuthResponse() throws Exception {
        RegisterRequest req = new RegisterRequest("alice@test.com", "pass123", "alice", "BUYER");
        when(service.register(any())).thenReturn(new AuthResponse("jwt-token", "user-1", "BUYER"));

        mockMvc.perform(post("/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value("jwt-token"))
                .andExpect(jsonPath("$.userId").value("user-1"))
                .andExpect(jsonPath("$.role").value("BUYER"));
    }

    @Test
    void register_publicEndpoint_noAuthRequired() throws Exception {
        // /users/register is in permitAll() — request succeeds without Authorization header
        RegisterRequest req = new RegisterRequest("bob@test.com", "pass456", "bob", "SELLER");
        when(service.register(any())).thenReturn(new AuthResponse("t", "user-2", "SELLER"));

        mockMvc.perform(post("/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isCreated());
    }

    @Test
    void register_missingEmail_returns400() throws Exception {
        // email is @Email @NotBlank — omitting it must fail Bean Validation before reaching service
        String badJson = "{\"password\":\"pass123\",\"username\":\"alice\"}";

        mockMvc.perform(post("/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_invalidRole_returns400() throws Exception {
        // role must match pattern BUYER|SELLER
        String badJson = "{\"email\":\"alice@test.com\",\"password\":\"pw\",\"username\":\"alice\",\"role\":\"ADMIN\"}";

        mockMvc.perform(post("/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badJson))
                .andExpect(status().isBadRequest());
    }

    // ── POST /users/login ─────────────────────────────────────────────────────

    @Test
    void login_validCredentials_returns200WithToken() throws Exception {
        LoginRequest req = new LoginRequest("alice@test.com", "pass123");
        when(service.login(any())).thenReturn(new AuthResponse("jwt-token", "user-1", "BUYER"));

        mockMvc.perform(post("/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("jwt-token"));
    }

    @Test
    void login_publicEndpoint_noAuthRequired() throws Exception {
        // /users/login is in permitAll() — request succeeds without Authorization header
        LoginRequest req = new LoginRequest("alice@test.com", "pass123");
        when(service.login(any())).thenReturn(new AuthResponse("t", "user-1", "BUYER"));

        mockMvc.perform(post("/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isOk());
    }

    @Test
    void login_missingPassword_returns400() throws Exception {
        // password is @NotBlank — omitting it must fail Bean Validation
        String badJson = "{\"email\":\"alice@test.com\"}";

        mockMvc.perform(post("/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badJson))
                .andExpect(status().isBadRequest());
    }

    // ── GET /users/{id} ───────────────────────────────────────────────────────

    @Test
    void getUser_authenticated_returns200WithUserJson() throws Exception {
        when(service.getById("user-1")).thenReturn(user("user-1", "alice@test.com", "alice"));

        mockMvc.perform(get("/users/user-1")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("user-1"))
                .andExpect(jsonPath("$.email").value("alice@test.com"))
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.role").value("BUYER"));
    }

    @Test
    void getUser_notFound_returns404() throws Exception {
        when(service.getById("missing"))
                .thenThrow(new ResponseStatusException(NOT_FOUND, "user not found"));

        mockMvc.perform(get("/users/missing")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getUser_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/users/user-1"))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /users/me ─────────────────────────────────────────────────────────

    @Test
    void me_authenticated_returnsOwnProfile() throws Exception {
        // JwtAuthFilter resolves principal.userId() = "user-1" from the mock Claims
        when(service.getById("user-1")).thenReturn(user("user-1", "alice@test.com", "alice"));

        mockMvc.perform(get("/users/me")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("user-1"));
    }

    @Test
    void me_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/users/me"))
                .andExpect(status().isUnauthorized());
    }
}
