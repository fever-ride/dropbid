package com.dropbid.user.controller;

import com.dropbid.user.dto.UserResponse;
import com.dropbid.user.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/internal")
public class InternalUserController {

    private final UserRepository repo;

    public InternalUserController(UserRepository repo) {
        this.repo = repo;
    }

    @GetMapping("/users")
    public List<UserResponse> allUsers() {
        return repo.findAll().stream().map(UserResponse::from).toList();
    }

    @GetMapping("/users/{id}")
    public UserResponse getUser(@PathVariable String id) {
        return repo.findById(id)
                .map(UserResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
