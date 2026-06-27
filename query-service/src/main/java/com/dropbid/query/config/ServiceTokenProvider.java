package com.dropbid.query.config;

import com.dropbid.shared.security.JwtUtil;
import org.springframework.stereotype.Component;

/**
 * Holds the service-to-service JWT used when calling /internal/** endpoints
 * on user-service and shop-service.
 */
@Component
public class ServiceTokenProvider {

    private final String token;

    public ServiceTokenProvider(JwtUtil jwtUtil) {
        this.token = jwtUtil.generateServiceToken("query-service");
    }

    public String getToken() {
        return token;
    }

    public String bearerHeader() {
        return "Bearer " + token;
    }
}
