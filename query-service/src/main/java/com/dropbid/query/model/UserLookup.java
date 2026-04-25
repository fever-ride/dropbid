package com.dropbid.query.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "user_lookup")
public class UserLookup {

    @Id
    private String userId;

    @Column(nullable = false)
    private String username;

    @Column(nullable = false)
    private String role;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    public String getUserId()                    { return userId; }
    public void setUserId(String userId)         { this.userId = userId; }

    public String getUsername()                   { return username; }
    public void setUsername(String username)      { this.username = username; }

    public String getRole()                      { return role; }
    public void setRole(String role)             { this.role = role; }

    public Instant getUpdatedAt()                { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt)  { this.updatedAt = updatedAt; }
}
