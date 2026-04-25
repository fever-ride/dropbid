package com.dropbid.user.model;

import com.dropbid.shared.IdGenerator;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "users")
public class User {

    @Id
    private String id;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null)        id = IdGenerator.newId();
        if (createdAt == null) createdAt = Instant.now();
    }

    // ── Getters / Setters ──────────────────────────────────────────────────

    public String getId()           { return id; }
    public void setId(String id)    { this.id = id; }

    public String getEmail()             { return email; }
    public void setEmail(String email)   { this.email = email; }

    public String getPasswordHash()                  { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getUsername()              { return username; }
    public void setUsername(String username) { this.username = username; }

    public Role getRole()          { return role; }
    public void setRole(Role role) { this.role = role; }

    public Instant getCreatedAt()               { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
