package com.network.monitoring.service;

import com.network.monitoring.entity.Session;
import com.network.monitoring.entity.User;
import com.network.monitoring.repository.SessionRepository;
import com.network.monitoring.repository.UserRepository;
import com.network.monitoring.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class AuthService {

    public static final Pattern PASSWORD_REGEX = Pattern.compile("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&])[A-Za-z\\d@$!%*?&]{8,}$");

    private final UserRepository userRepository;
    private final SessionRepository sessionRepository;
    private final JwtService jwtService;
    private final SessionService sessionService;
    private final PasswordEncoder passwordEncoder;
    private final long jwtExpirySeconds;

    public AuthService(UserRepository userRepository,
                       SessionRepository sessionRepository,
                       JwtService jwtService,
                       SessionService sessionService,
                       PasswordEncoder passwordEncoder,
                       @Value("${app.jwt-expiry-seconds:604800}") long jwtExpirySeconds) {
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.jwtService = jwtService;
        this.sessionService = sessionService;
        this.passwordEncoder = passwordEncoder;
        this.jwtExpirySeconds = jwtExpirySeconds <= 0 ? 604800 : jwtExpirySeconds;
    }

    public AuthResult register(String email, String password) {
        validateRequired(email, password);
        if (!PASSWORD_REGEX.matcher(password).matches()) {
            throw new IllegalArgumentException("Password must be at least 8 characters long and include uppercase, lowercase, digit, and special character.");
        }

        Optional<User> existing = userRepository.findByEmail(email);
        if (existing.isPresent()) {
            throw new IllegalStateException("User already exists with this email");
        }

        User user = new User();
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(password));
        user.setRole(userRepository.count() == 0 ? "SUPER_ADMIN" : "USER");
        user.setStatus("ACTIVE");
        User saved = userRepository.save(user);
        return createSessionAndRespond(saved);
    }

    public AuthResult login(String email, String password) {
        validateRequired(email, password);

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Invalid Credentials"));

        boolean hashedPasswordMatch = passwordEncoder.matches(password, user.getPassword());
        boolean legacyPasswordMatch = Objects.equals(user.getPassword(), password);
        if (!hashedPasswordMatch && !legacyPasswordMatch) {
            throw new IllegalArgumentException("Invalid Credentials");
        }

        if (legacyPasswordMatch) {
            user.setPassword(passwordEncoder.encode(password));
            userRepository.save(user);
        }
        if (user.getStatus() != null && "BLOCKED".equalsIgnoreCase(user.getStatus())) {
            throw new IllegalArgumentException("User is blocked.");
        }

        return createSessionAndRespond(user);
    }

    @Transactional
    public void logout(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        sessionService.deleteByToken(token);
    }

    private AuthResult createSessionAndRespond(User user) {
        String token = jwtService.generateToken(user.getId());
        Session session = new Session();
        session.setToken(token);
        session.setUser(user);
        session.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        session.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(jwtExpirySeconds));
        sessionRepository.save(session);
        return new AuthResult(token, user);
    }

    private void validateRequired(String email, String password) {
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            throw new IllegalArgumentException("All fields are required");
        }
    }

    public record AuthResult(String token, User user) {
    }
}
