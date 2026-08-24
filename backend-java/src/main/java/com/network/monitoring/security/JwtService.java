package com.network.monitoring.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

@Service
public class JwtService {

    private final Algorithm algorithm;

    public JwtService(@Value("${app.jwt-secret}") String secret) {
        if (secret == null || secret.isBlank() || "change-me-in-env".equals(secret)) {
            throw new IllegalStateException("JWT secret must be configured through JWT_SECRET.");
        }
        this.algorithm = Algorithm.HMAC256(secret);
    }

    public String generateToken(Long userId) {
        return JWT.create()
                .withClaim("userId", userId)
                .withExpiresAt(Date.from(Instant.now().plus(7, ChronoUnit.DAYS)))
                .sign(algorithm);
    }

    public DecodedJWT verifyToken(String token) {
        try {
            return JWT.require(algorithm).build().verify(token);
        } catch (JWTVerificationException ex) {
            return null;
        }
    }
}
