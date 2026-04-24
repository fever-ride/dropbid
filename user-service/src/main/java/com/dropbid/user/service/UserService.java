package com.dropbid.user.service;

import com.dropbid.shared.events.UserUpdatedEvent;
import com.dropbid.shared.security.JwtUtil;
import com.dropbid.user.dto.AuthResponse;
import com.dropbid.user.dto.LoginRequest;
import com.dropbid.user.dto.RegisterRequest;
import com.dropbid.user.events.UserEventPublisher;
import com.dropbid.user.model.Role;
import com.dropbid.user.model.User;
import com.dropbid.user.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@Service
public class UserService {

    private final UserRepository repo;
    private final JwtUtil jwtUtil;
    private final UserEventPublisher eventPublisher;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public UserService(UserRepository repo, JwtUtil jwtUtil, UserEventPublisher eventPublisher) {
        this.repo            = repo;
        this.jwtUtil         = jwtUtil;
        this.eventPublisher  = eventPublisher;
    }

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        if (repo.existsByEmail(req.email())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "email already registered");
        }
        User user = new User();
        user.setEmail(req.email());
        user.setPasswordHash(encoder.encode(req.password()));
        user.setUsername(req.username());
        user.setRole(req.role() != null ? Role.valueOf(req.role()) : Role.BUYER);
        repo.save(user);

        eventPublisher.publish(new UserUpdatedEvent(
                user.getId(), user.getUsername(), user.getRole().name(),
                Instant.now().toString()));

        String token = jwtUtil.generateToken(user.getId(), user.getRole().name());
        return new AuthResponse(token, user.getId(), user.getRole().name());
    }

    public AuthResponse login(LoginRequest req) {
        User user = repo.findByEmail(req.email())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials"));
        if (!encoder.matches(req.password(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials");
        }
        String token = jwtUtil.generateToken(user.getId(), user.getRole().name());
        return new AuthResponse(token, user.getId(), user.getRole().name());
    }

    public User getById(String userId) {
        return repo.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"));
    }
}
