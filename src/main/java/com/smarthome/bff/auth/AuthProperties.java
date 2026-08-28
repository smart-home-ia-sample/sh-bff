package com.smarthome.bff.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Auth configuration. One demo user; credentials come from the environment
 * ({@code DEMO_USER} / {@code DEMO_PASS_HASH}), the JWT secret from
 * {@code JWT_SECRET}. There is no user table — {@code homes.user_id} just
 * references this username.
 */
@ConfigurationProperties(prefix = "bff.auth")
public record AuthProperties(
        String jwtSecret,
        long tokenTtlMinutes,
        String demoUser,
        String demoPassHash
) {
}
