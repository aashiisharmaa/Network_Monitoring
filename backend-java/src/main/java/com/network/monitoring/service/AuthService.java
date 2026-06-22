package com.network.monitoring.service;

import com.network.monitoring.entity.Session;
import com.network.monitoring.entity.User;
import com.network.monitoring.repository.SessionRepository;
import com.network.monitoring.repository.UserRepository;
import com.network.monitoring.security.JwtService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
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
    private final BCryptPasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository,
                       SessionRepository sessionRepository,
                       JwtService jwtService) {
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.jwtService = jwtService;
        this.passwordEncoder = new BCryptPasswordEncoder();
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

        return createSessionAndRespond(user);
    }

    @Transactional
    public void logout(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        sessionRepository.findByToken(token).ifPresent(sessionRepository::delete);
    }

    private AuthResult createSessionAndRespond(User user) {
        String token = jwtService.generateToken(user.getId());
        Session session = new Session();
        session.setToken(token);
        session.setUser(user);
        session.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        session.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusDays(7));
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
