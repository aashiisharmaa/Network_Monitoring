package com.network.monitoring.security;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.network.monitoring.entity.Session;
import com.network.monitoring.repository.SessionRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final SessionRepository sessionRepository;

    public JwtAuthenticationFilter(JwtService jwtService, SessionRepository sessionRepository) {
        this.jwtService = jwtService;
        this.sessionRepository = sessionRepository;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return HttpMethod.OPTIONS.matches(request.getMethod())
                || path.startsWith("/auth")
                || path.equals("/error");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"message\":\"Missing or invalid token\"}");
            return;
        }

        String token = authHeader.substring("Bearer ".length()).trim();
        DecodedJWT decoded = jwtService.verifyToken(token);
        if (decoded == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"message\":\"Invalid token\"}");
            return;
        }

        Optional<Session> session = sessionRepository.findByToken(token);
        if (session.isEmpty() || session.get().getExpiresAt().isBefore(LocalDateTime.now(ZoneOffset.UTC))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"message\":\"Session expired or invalid\"}");
            return;
        }

        request.setAttribute("userId", decoded.getClaim("userId").asLong());
        filterChain.doFilter(request, response);
    }
}
