package com.smarthome.bff.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/** Mints and validates HS256 access tokens. Claims: sub, iat, exp. */
@Service
public class JwtService {

    private final SecretKey key;
    private final long ttlMinutes;

    public JwtService(AuthProperties props) {
        byte[] secret = props.jwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException(
                    "bff.auth.jwt-secret must be at least 32 bytes for HS256 (set JWT_SECRET)");
        }
        this.key = Keys.hmacShaKeyFor(secret);
        this.ttlMinutes = props.tokenTtlMinutes();
    }

    public String issue(String subject) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(subject)
                .issuedAt(java.util.Date.from(now))
                .expiration(java.util.Date.from(now.plus(ttlMinutes, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();
    }

    /** @return the {@code sub} claim if the token is valid and unexpired, else empty. */
    public Optional<String> verify(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.ofNullable(claims.getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public long tokenTtlMinutes() {
        return ttlMinutes;
    }
}
