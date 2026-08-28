package com.smarthome.bff.auth;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-0123456789";

    private JwtService service(long ttlMinutes) {
        return new JwtService(new AuthProperties(SECRET, ttlMinutes, "demo", "irrelevant"));
    }

    @Test
    void issuesAndVerifiesRoundTrip() {
        JwtService jwt = service(60);
        String token = jwt.issue("demo");
        assertThat(jwt.verify(token)).contains("demo");
    }

    @Test
    void rejectsExpiredToken() {
        String token = service(-1).issue("demo");
        assertThat(service(60).verify(token)).isEmpty();
    }

    @Test
    void rejectsTamperedToken() {
        String token = service(60).issue("demo");
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("a") ? "b" : "a") + "c";
        assertThat(service(60).verify(tampered)).isEmpty();
    }

    @Test
    void rejectsTokenSignedWithAnotherSecret() {
        JwtService other = new JwtService(
                new AuthProperties("another-secret-another-secret-xxxxxx-0123456789", 60, "demo", "x"));
        String token = other.issue("demo");
        assertThat(service(60).verify(token)).isEmpty();
    }

    @Test
    void rejectsGarbage() {
        assertThat(service(60).verify("not-a-jwt")).isEqualTo(Optional.empty());
    }

    @Test
    void rejectsShortSecret() {
        try {
            new JwtService(new AuthProperties("too-short", 60, "demo", "x"));
            throw new AssertionError("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessageContaining("32 bytes");
        }
    }
}
