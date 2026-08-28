package com.smarthome.bff.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Guards every {@code /api/**} route. A valid {@code Authorization: Bearer <jwt>}
 * is required; the resolved subject is stashed as the {@code bff.user} request
 * attribute for downstream controllers and gateway routes. Anything else is a
 * 401 before the request reaches a handler or a proxy.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String USER_ATTRIBUTE = "bff.user";

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        Optional<String> subject = bearerToken(request).flatMap(jwtService::verify);
        if (subject.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"missing or invalid token\"}");
            return;
        }

        request.setAttribute(USER_ATTRIBUTE, subject.get());
        chain.doFilter(request, response);
    }

    private static Optional<String> bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.empty();
        }
        String value = header.substring(7).trim();
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }
}
