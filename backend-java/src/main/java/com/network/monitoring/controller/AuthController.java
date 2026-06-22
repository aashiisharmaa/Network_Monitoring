package com.network.monitoring.controller;

import com.network.monitoring.entity.User;
import com.network.monitoring.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, Object> body) {
        try {
            String email = body.get("email") == null ? null : String.valueOf(body.get("email"));
            String password = body.get("password") == null ? null : String.valueOf(body.get("password"));
            AuthService.AuthResult result = authService.register(email, password);
            return ResponseEntity.ok(buildAuthResponse("User signed up successfully", result));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(errorResponse(ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(409).body(errorResponse(ex.getMessage()));
        } catch (Exception ex) {
            return ResponseEntity.status(500).body(errorResponse("Internal server error occurred"));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, Object> body) {
        try {
            String email = body.get("email") == null ? null : String.valueOf(body.get("email"));
            String password = body.get("password") == null ? null : String.valueOf(body.get("password"));
            AuthService.AuthResult result = authService.login(email, password);
            return ResponseEntity.ok(buildAuthResponse("User logged in successfully", result));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(401).body(errorResponse(ex.getMessage()));
        } catch (Exception ex) {
            return ResponseEntity.status(500).body(errorResponse("Internal server error occurred"));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(@RequestBody(required = false) Map<String, Object> body,
                                                      jakarta.servlet.http.HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || authHeader.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "No token provided"));
        }

        String token = authHeader.startsWith("Bearer ") ? authHeader.substring("Bearer ".length()).trim() : authHeader.trim();
        authService.logout(token);
        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }

    private Map<String, Object> buildAuthResponse(String message, AuthService.AuthResult result) {
        Map<String, Object> data = new LinkedHashMap<>();
        User user = result.user();
        data.put("token", result.token());
        data.put("user", Map.of(
                "id", user.getId(),
                "email", user.getEmail()
        ));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", message);
        response.put("success", true);
        response.put("data", data);
        return response;
    }

    private Map<String, Object> errorResponse(String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", message);
        response.put("success", false);
        return response;
    }
}
