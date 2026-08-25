package com.network.monitoring.service;

import com.network.monitoring.entity.User;
import com.network.monitoring.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class RoleAuthorizationService {

    private final UserRepository userRepository;

    public RoleAuthorizationService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getPrincipal() == null) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof Long value) {
            return value;
        }
        try {
            return Long.parseLong(String.valueOf(principal));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public void requireSuperAdmin() {
        Long userId = currentUserId();
        if (userId == null) {
            throw new IllegalArgumentException("Authenticated user is required.");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Authenticated user was not found."));
        String role = user.getRole();
        if (role == null || role.isBlank()) {
            return;
        }
        if (!"SUPER_ADMIN".equalsIgnoreCase(role)) {
            throw new IllegalArgumentException("Super admin access is required.");
        }
    }

    public boolean isCurrentUserSuperAdmin() {
        Long userId = currentUserId();
        if (userId == null) {
            return false;
        }
        return userRepository.findById(userId)
                .map(user -> {
                    String role = user.getRole();
                    return role == null || role.isBlank() || "SUPER_ADMIN".equalsIgnoreCase(role);
                })
                .orElse(false);
    }

    public Long currentUserCompanyIdOrNull() {
        Long userId = currentUserId();
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId)
                .map(User::getCompanyId)
                .orElse(null);
    }

    public Long effectiveCompanyId(Long requestedCompanyId) {
        if (isCurrentUserSuperAdmin()) {
            return requestedCompanyId;
        }
        return currentUserCompanyIdOrNull();
    }
}
