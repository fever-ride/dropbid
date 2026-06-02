package com.dropbid.shared.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link JwtAuthFilter}.
 *
 * Spring's {@link MockHttpServletRequest} / {@link MockHttpServletResponse} are used
 * so no web container is needed.  The {@link SecurityContextHolder} is cleared after
 * each test to avoid cross-test pollution.
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthFilterTest {

    @Mock JwtUtil      jwtUtil;
    @Mock FilterChain  chain;

    JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthFilter(jwtUtil);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── 1. No Authorization header → pass-through ──────────────────────────────

    @Test
    void noAuthorizationHeader_callsFilterChainWithoutAuth() throws Exception {
        MockHttpServletRequest  request  = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(response.getStatus()).isEqualTo(200); // unchanged
    }

    // ── 2. Header present but not "Bearer " prefix → pass-through ─────────────

    @Test
    void headerNotBearer_callsFilterChainWithoutAuth() throws Exception {
        MockHttpServletRequest  request  = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    // ── 3. Valid Bearer token → SecurityContext populated ─────────────────────

    @Test
    void validBearerToken_populatesSecurityContextAndCallsChain() throws Exception {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("user-42");
        when(claims.get("role", String.class)).thenReturn("BUYER");
        when(jwtUtil.validateToken("good-token")).thenReturn(claims);

        MockHttpServletRequest  request  = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("Authorization", "Bearer good-token");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(((UserPrincipal) auth.getPrincipal()).userId()).isEqualTo("user-42");
        assertThat(((UserPrincipal) auth.getPrincipal()).role()).isEqualTo("BUYER");
        assertThat(auth.getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_BUYER");
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void validBearerToken_sellerRole_correctAuthoritySet() throws Exception {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("seller-7");
        when(claims.get("role", String.class)).thenReturn("SELLER");
        when(jwtUtil.validateToken("seller-token")).thenReturn(claims);

        MockHttpServletRequest  request  = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("Authorization", "Bearer seller-token");

        filter.doFilterInternal(request, response, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth.getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_SELLER");
    }

    // ── 4. Invalid / expired token → 401, chain NOT called ────────────────────

    @Test
    void invalidToken_returns401AndDoesNotCallFilterChain() throws Exception {
        when(jwtUtil.validateToken("bad-token"))
                .thenThrow(new JwtException("signature mismatch"));

        MockHttpServletRequest  request  = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("Authorization", "Bearer bad-token");

        filter.doFilterInternal(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Invalid or expired token");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
