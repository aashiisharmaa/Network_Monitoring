package com.network.monitoring.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.network.monitoring.service.LocalLicenseService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class LicenseGuardFilter extends OncePerRequestFilter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LocalLicenseService localLicenseService;

    public LicenseGuardFilter(LocalLicenseService localLicenseService) {
        this.localLicenseService = localLicenseService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return HttpMethod.OPTIONS.matches(request.getMethod())
                || path.startsWith("/auth")
                || path.startsWith("/api/v1/auth")
                || path.startsWith("/local-license")
                || path.startsWith("/api/v1/local-license")
                || path.startsWith("/companies")
                || path.startsWith("/api/v1/companies")
                || path.startsWith("/users")
                || path.startsWith("/api/v1/users")
                || path.startsWith("/licenses")
                || path.startsWith("/api/v1/licenses")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs")
                || path.equals("/error");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Map<String, Object> status = localLicenseService.status();
        if (Boolean.TRUE.equals(status.get("valid"))) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(402);
        response.setContentType("application/json");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", "Valid license is required.");
        body.put("data", status);
        response.getWriter().write(MAPPER.writeValueAsString(body));
    }
}
