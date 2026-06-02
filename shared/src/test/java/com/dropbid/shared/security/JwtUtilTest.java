package com.dropbid.shared.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link JwtUtil}.
 *
 * No Spring context needed — {@link JwtUtil} is self-contained: it accepts
 * the JWT secret as a constructor argument, pads short keys to 32 bytes,
 * and provides generate/validate round-trips with JJWT.
 */
class JwtUtilTest {

    // 32-byte secret — no padding needed
    private static final String LONG_SECRET  = "this-is-a-32-byte-secret-for-hs!";
    // Short secret — exercises the padding branch
    private static final String SHORT_SECRET = "short";

    // ── name() ────────────────────────────────────────────────────────────────

    // ── generateToken / validateToken round-trip ──────────────────────────────

    @Test
    void generateAndValidate_subjectAndRolePresentInClaims() {
        JwtUtil util = new JwtUtil(LONG_SECRET);

        String token = util.generateToken("user-123", "BUYER");
        Claims claims = util.validateToken(token);

        assertThat(claims.getSubject()).isEqualTo("user-123");
        assertThat(claims.get("role", String.class)).isEqualTo("BUYER");
    }

    @Test
    void generateToken_shortSecret_paddsKeyAndSucceeds() {
        // Constructor must not throw even for a short secret
        JwtUtil util = new JwtUtil(SHORT_SECRET);

        String token = util.generateToken("user-abc", "SELLER");
        Claims claims = util.validateToken(token);

        assertThat(claims.getSubject()).isEqualTo("user-abc");
        assertThat(claims.get("role", String.class)).isEqualTo("SELLER");
    }

    @Test
    void generateToken_differentInstances_sameSecretValidatesCorrectly() {
        JwtUtil issuer   = new JwtUtil(LONG_SECRET);
        JwtUtil verifier = new JwtUtil(LONG_SECRET);

        String token = issuer.generateToken("user-x", "ADMIN");
        Claims claims = verifier.validateToken(token);

        assertThat(claims.getSubject()).isEqualTo("user-x");
    }

    // ── validateToken — failure paths ─────────────────────────────────────────

    @Test
    void validateToken_tamperedSignature_throwsJwtException() {
        JwtUtil util = new JwtUtil(LONG_SECRET);
        String token = util.generateToken("user-1", "BUYER");

        // Corrupt the signature segment (last part after the second dot)
        String tampered = token.substring(0, token.lastIndexOf('.') + 1) + "INVALIDSIG";

        assertThatThrownBy(() -> util.validateToken(tampered))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void validateToken_tokenSignedWithDifferentSecret_throwsJwtException() {
        JwtUtil issuer   = new JwtUtil("secret-one-xxxxxxxxxxxxxxxxxxxxxxxxx");
        JwtUtil verifier = new JwtUtil("secret-two-xxxxxxxxxxxxxxxxxxxxxxxxx");

        String token = issuer.generateToken("user-1", "BUYER");

        assertThatThrownBy(() -> verifier.validateToken(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void validateToken_randomString_throwsJwtException() {
        JwtUtil util = new JwtUtil(LONG_SECRET);

        assertThatThrownBy(() -> util.validateToken("not.a.jwt"))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void validateToken_emptyString_throwsException() {
        // JJWT may throw IllegalArgumentException (not JwtException) for a blank
        // input since it fails the "not empty" precondition before parsing.
        // Both are acceptable — any RuntimeException signals rejection.
        JwtUtil util = new JwtUtil(LONG_SECRET);

        assertThatThrownBy(() -> util.validateToken(""))
                .isInstanceOf(RuntimeException.class);
    }
}
