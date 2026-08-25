package com.network.monitoring.service;

import com.network.monitoring.entity.User;
import com.network.monitoring.repository.UserRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class UserManagementService {

    private static final String BOOTSTRAP_SUPER_ADMIN_EMAIL = "aditya.singh@vinfocom.co.in";
    private static final String BOOTSTRAP_SUPER_ADMIN_PASSWORD = "Adi@123";
    private static final String ROLE_SUPER_ADMIN = "SUPER_ADMIN";
    private static final String ROLE_USER = "USER";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_BLOCKED = "BLOCKED";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RoleAuthorizationService roleAuthorizationService;

    public UserManagementService(UserRepository userRepository,
                                 PasswordEncoder passwordEncoder,
                                 RoleAuthorizationService roleAuthorizationService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.roleAuthorizationService = roleAuthorizationService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureBootstrapSuperAdmin() {
        Optional<User> existing = userRepository.findByEmail(BOOTSTRAP_SUPER_ADMIN_EMAIL);
        User user = existing.orElseGet(User::new);
        user.setEmail(BOOTSTRAP_SUPER_ADMIN_EMAIL);
        if (user.getPassword() == null || !passwordEncoder.matches(BOOTSTRAP_SUPER_ADMIN_PASSWORD, user.getPassword())) {
            user.setPassword(passwordEncoder.encode(BOOTSTRAP_SUPER_ADMIN_PASSWORD));
        }
        user.setRole(ROLE_SUPER_ADMIN);
        user.setStatus(STATUS_ACTIVE);
        userRepository.save(user);
    }

    public List<Map<String, Object>> listUsers() {
        roleAuthorizationService.requireSuperAdmin();
        return userRepository.findAll().stream()
                .map(this::payload)
                .toList();
    }

    public Map<String, Object> createUser(Map<String, Object> body) {
        roleAuthorizationService.requireSuperAdmin();

        String email = string(body.get("email"));
        String password = string(body.get("password"));
        validateRequired(email, password);
        email = email.trim().toLowerCase();

        if (!AuthService.PASSWORD_REGEX.matcher(password).matches()) {
            throw new IllegalArgumentException("Password must be at least 8 characters long and include uppercase, lowercase, digit, and special character.");
        }

        if (userRepository.findByEmail(email).isPresent()) {
            throw new IllegalStateException("User already exists with this email");
        }

        User user = new User();
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(password));
        user.setCompanyId(longValue(body.get("companyId"), body.get("company_id")));
        user.setRole(normalizeRole(string(body.get("role"))));
        user.setStatus(normalizeStatus(string(body.get("status"))));
        return payload(userRepository.save(user));
    }

    private void validateRequired(String email, String password) {
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            throw new IllegalArgumentException("Email and password are required.");
        }
    }

    private String normalizeRole(String value) {
        if (value == null || value.isBlank()) {
            return ROLE_USER;
        }
        String normalized = value.trim().toUpperCase();
        if (ROLE_SUPER_ADMIN.equals(normalized) || ROLE_USER.equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("Role must be USER or SUPER_ADMIN.");
    }

    private String normalizeStatus(String value) {
        if (value == null || value.isBlank()) {
            return STATUS_ACTIVE;
        }
        String normalized = value.trim().toUpperCase();
        if (STATUS_ACTIVE.equals(normalized) || STATUS_BLOCKED.equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("Status must be ACTIVE or BLOCKED.");
    }

    private Map<String, Object> payload(User user) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", user.getId());
        item.put("email", user.getEmail());
        item.put("companyId", user.getCompanyId());
        item.put("role", user.getRole() == null || user.getRole().isBlank() ? ROLE_USER : user.getRole());
        item.put("status", user.getStatus() == null || user.getStatus().isBlank() ? STATUS_ACTIVE : user.getStatus());
        return item;
    }

    private String string(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private Long longValue(Object... values) {
        for (Object value : values) {
            if (value instanceof Number number) {
                return number.longValue();
            }
            String text = string(value);
            if (text != null) {
                try {
                    return Long.parseLong(text);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }
}
