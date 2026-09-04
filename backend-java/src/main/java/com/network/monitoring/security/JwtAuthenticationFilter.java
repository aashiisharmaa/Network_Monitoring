package com.network.monitoring.security;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.network.monitoring.service.SessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final SessionService sessionService;

    public JwtAuthenticationFilter(JwtService jwtService, SessionService sessionService) {
        this.jwtService = jwtService;
        this.sessionService = sessionService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return HttpMethod.OPTIONS.matches(request.getMethod())
                || isPublicReadOnlyGet(request, path)
                || path.startsWith("/auth")
                || path.startsWith("/api/v1/auth")
                || path.startsWith("/health")
                || path.startsWith("/api/v1/health")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs")
                || path.equals("/error");
    }

    private boolean isPublicReadOnlyGet(HttpServletRequest request, String path) {
        if (!HttpMethod.GET.matches(request.getMethod())) {
            return false;
        }

        return path.startsWith("/dashboard")
                || path.startsWith("/api/v1/dashboard")
                || path.startsWith("/kpi")
                || path.startsWith("/api/v1/kpi")
                || path.startsWith("/sites")
                || path.startsWith("/api/v1/sites")
                || path.startsWith("/upload")
                || path.startsWith("/api/v1/upload")
                || path.equals("/setting/getsettings")
                || path.equals("/api/v1/setting/getsettings")
                || path.equals("/companies")
                || path.equals("/api/v1/companies");
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

        if (!sessionService.isTokenActive(token)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"message\":\"Session expired or invalid\"}");
            return;
        }

        Long userId = decoded.getClaim("userId").asLong();
        request.setAttribute("userId", userId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of())
        );
        filterChain.doFilter(request, response);
    }
}
