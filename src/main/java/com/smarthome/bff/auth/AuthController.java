package com.smarthome.bff.auth;

import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** The only public route. Verifies the single demo user against the configured bcrypt hash. */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthProperties props;
    private final JwtService jwtService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthController(AuthProperties props, JwtService jwtService) {
        this.props = props;
        this.jwtService = jwtService;
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    /** JSON body: {"username": "...", "password": "..."}. */
    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        return authenticate(request.username(), request.password());
    }

    /**
     * Form-encoded body (`application/x-www-form-urlencoded` or `multipart/form-data`):
     * `username=...&password=...`. Lets standard form posts / OAuth-style clients log in
     * without hand-crafting a JSON body.
     */
    @PostMapping(value = "/login", consumes = {
            MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            MediaType.MULTIPART_FORM_DATA_VALUE
    })
    public ResponseEntity<?> loginForm(@RequestParam String username, @RequestParam String password) {
        return authenticate(username, password);
    }

    private ResponseEntity<?> authenticate(String username, String password) {
        // A null/blank field surfaces as 400 (missing form param, or bcrypt
        // rejecting a null raw password via ApiExceptionHandler) rather than 401.
        boolean userMatches = props.demoUser().equals(username);
        boolean passMatches = encoder.matches(password, props.demoPassHash());
        if (!userMatches || !passMatches) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid credentials"));
        }
        String token = jwtService.issue(username);
        return ResponseEntity.ok(Map.of(
                "access_token", token,
                "token_type", "Bearer",
                "expires_in", jwtService.tokenTtlMinutes() * 60
        ));
    }
}
