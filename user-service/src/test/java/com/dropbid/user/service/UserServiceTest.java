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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock UserRepository     repo;
    @Mock JwtUtil            jwtUtil;
    @Mock UserEventPublisher eventPublisher;

    UserService service;

    // A real encoder to pre-hash passwords for login tests
    final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        service = new UserService(repo, jwtUtil, eventPublisher);
        // lenient: some tests (404/409/401 paths) never reach generateToken
        lenient().when(jwtUtil.generateToken(any(), any())).thenReturn("mock-jwt");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Make repo.save return the same User with a synthetic ID (replaces @PrePersist). */
    private void stubSaveWithId() {
        when(repo.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId("user-gen-id");
            return u;
        });
    }

    static RegisterRequest reg(String email, String password, String username, String role) {
        return new RegisterRequest(email, password, username, role);
    }

    static LoginRequest login(String email, String password) {
        return new LoginRequest(email, password);
    }

    // ── register ───────────────────────────────────────────────────────────────

    @Test
    void register_newEmail_returnsAuthResponseWithToken() {
        when(repo.existsByEmail("alice@test.com")).thenReturn(false);
        stubSaveWithId();

        AuthResponse resp = service.register(reg("alice@test.com", "pass123", "alice", "BUYER"));

        assertThat(resp.token()).isEqualTo("mock-jwt");
        assertThat(resp.userId()).isEqualTo("user-gen-id");
        assertThat(resp.role()).isEqualTo("BUYER");
    }

    @Test
    void register_nullRole_defaultsToBuyer() {
        when(repo.existsByEmail("alice@test.com")).thenReturn(false);
        stubSaveWithId();

        service.register(reg("alice@test.com", "pass123", "alice", null));

        ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
        verify(repo).save(cap.capture());
        assertThat(cap.getValue().getRole()).isEqualTo(Role.BUYER);
    }

    @Test
    void register_explicitSellerRole_setsRoleSeller() {
        when(repo.existsByEmail("bob@test.com")).thenReturn(false);
        stubSaveWithId();

        service.register(reg("bob@test.com", "pass456", "bob", "SELLER"));

        ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
        verify(repo).save(cap.capture());
        assertThat(cap.getValue().getRole()).isEqualTo(Role.SELLER);
    }

    @Test
    void register_passwordIsHashed_notStoredAsPlaintext() {
        when(repo.existsByEmail("carol@test.com")).thenReturn(false);
        stubSaveWithId();

        service.register(reg("carol@test.com", "secret99", "carol", null));

        ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
        verify(repo).save(cap.capture());
        String hash = cap.getValue().getPasswordHash();
        assertThat(hash).isNotEqualTo("secret99");
        assertThat(encoder.matches("secret99", hash)).isTrue();
    }

    @Test
    void register_publishesUserUpdatedEvent() {
        when(repo.existsByEmail("dave@test.com")).thenReturn(false);
        stubSaveWithId();

        service.register(reg("dave@test.com", "pw", "dave", "BUYER"));

        ArgumentCaptor<UserUpdatedEvent> cap = ArgumentCaptor.forClass(UserUpdatedEvent.class);
        verify(eventPublisher).publish(cap.capture());
        assertThat(cap.getValue().username()).isEqualTo("dave");
        assertThat(cap.getValue().role()).isEqualTo("BUYER");
    }

    @Test
    void register_duplicateEmail_throws409() {
        when(repo.existsByEmail("dup@test.com")).thenReturn(true);

        assertThatThrownBy(() -> service.register(reg("dup@test.com", "pw", "dup", null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409")
                .hasMessageContaining("email already registered");

        verify(repo, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    // ── login ──────────────────────────────────────────────────────────────────

    @Test
    void login_correctCredentials_returnsToken() {
        User stored = new User();
        stored.setId("user-1");
        stored.setEmail("eve@test.com");
        stored.setRole(Role.BUYER);
        stored.setPasswordHash(encoder.encode("correct-pw"));
        when(repo.findByEmail("eve@test.com")).thenReturn(Optional.of(stored));

        AuthResponse resp = service.login(login("eve@test.com", "correct-pw"));

        assertThat(resp.token()).isEqualTo("mock-jwt");
        assertThat(resp.userId()).isEqualTo("user-1");
        verify(jwtUtil).generateToken("user-1", "BUYER");
    }

    @Test
    void login_unknownEmail_throws401() {
        when(repo.findByEmail("ghost@test.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(login("ghost@test.com", "pw")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("401");
    }

    @Test
    void login_wrongPassword_throws401() {
        User stored = new User();
        stored.setId("user-2");
        stored.setEmail("frank@test.com");
        stored.setRole(Role.SELLER);
        stored.setPasswordHash(encoder.encode("real-pw"));
        when(repo.findByEmail("frank@test.com")).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.login(login("frank@test.com", "wrong-pw")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("401");
    }

    // ── getById ────────────────────────────────────────────────────────────────

    @Test
    void getById_found_returnsUser() {
        User user = new User();
        user.setId("user-3");
        when(repo.findById("user-3")).thenReturn(Optional.of(user));

        User result = service.getById("user-3");

        assertThat(result.getId()).isEqualTo("user-3");
    }

    @Test
    void getById_notFound_throws404() {
        when(repo.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById("missing"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }
}
