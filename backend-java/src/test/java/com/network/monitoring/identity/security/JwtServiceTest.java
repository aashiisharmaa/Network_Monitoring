package com.network.monitoring.identity.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {
    @Test void validTokensHaveIdentityExpiryAndDifferentSessionIds() {
        var service=new JwtService("synthetic-jwt-test-secret-only",60);
        var first=service.verifyToken(service.generateToken(42L));
        var second=service.verifyToken(service.generateToken(42L));
        assertEquals(42L,first.getClaim("userId").asLong());
        assertNotEquals(first.getId(),second.getId());assertTrue(first.getExpiresAt().after(new Date()));
    }
    @Test void expiredForeignSignatureAndMalformedTokensFailVerification() {
        var service=new JwtService("synthetic-jwt-test-secret-only",60);
        String expired=JWT.create().withClaim("userId",42L).withExpiresAt(Date.from(Instant.now().minusSeconds(60)))
                .sign(Algorithm.HMAC256("synthetic-jwt-test-secret-only"));
        assertNull(service.verifyToken(expired));assertNull(service.verifyToken("not-a-jwt"));
        assertNull(service.verifyToken(new JwtService("another-synthetic-key",60).generateToken(42L)));
    }
    @Test void developmentDefaultAndMissingSecretsFailStartup() {
        assertThrows(IllegalStateException.class,()->new JwtService("change-me-in-env",60));
        assertThrows(IllegalStateException.class,()->new JwtService("",60));
    }
}
