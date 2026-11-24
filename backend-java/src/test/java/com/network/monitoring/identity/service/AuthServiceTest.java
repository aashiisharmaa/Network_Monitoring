package com.network.monitoring.identity.service;

import com.network.monitoring.identity.entity.User;
import com.network.monitoring.identity.entity.Session;
import com.network.monitoring.identity.repository.UserRepository;
import com.network.monitoring.identity.repository.SessionRepository;
import com.network.monitoring.identity.security.JwtService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthServiceTest {
    final UserRepository users=mock(UserRepository.class);
    final SessionRepository sessions=mock(SessionRepository.class);
    final SessionService sessionService=mock(SessionService.class);
    final BCryptPasswordEncoder passwords=new BCryptPasswordEncoder(4);
    final AuthService auth=new AuthService(users,sessions,new JwtService("synthetic-auth-test-key",60),sessionService,passwords,60);
    @Test void registrationHashesPasswordAndAppliesExistingBootstrapRolePolicy() {
        when(users.findByEmail(anyString())).thenReturn(Optional.empty());
        when(users.save(any())).thenAnswer(call->{User u=call.getArgument(0);u.setId(42L);return u;});
        when(users.count()).thenReturn(0L,10L);
        var first=auth.register("first@example.test","TestOnly@123");
        assertEquals("SUPER_ADMIN",first.user().getRole());assertNotEquals("TestOnly@123",first.user().getPassword());
        assertTrue(passwords.matches("TestOnly@123",first.user().getPassword()));
        assertEquals("USER",auth.register("second@example.test","TestOnly@123").user().getRole());
    }
    @Test void missingWeakAndDuplicateRegistrationInputsDoNotCreateAccounts() {
        assertThrows(IllegalArgumentException.class,()->auth.register("","TestOnly@123"));
        assertThrows(IllegalArgumentException.class,()->auth.register("new@example.test","weak"));
        when(users.findByEmail("existing@example.test")).thenReturn(Optional.of(new User()));
        assertThrows(IllegalStateException.class,()->auth.register("existing@example.test","TestOnly@123"));
        verify(users,never()).save(any());verifyNoInteractions(sessions);
    }
    @Test void incorrectAndBlockedLoginsDoNotCreateSessions() {
        var user=new User();user.setId(42L);user.setPassword(passwords.encode("TestOnly@123"));user.setStatus("BLOCKED");
        when(users.findByEmail("blocked@example.test")).thenReturn(Optional.of(user));
        assertThrows(IllegalArgumentException.class,()->auth.login("unknown@example.test","TestOnly@123"));
        assertThrows(IllegalArgumentException.class,()->auth.login("blocked@example.test","Wrong@123"));
        assertThrows(IllegalArgumentException.class,()->auth.login("blocked@example.test","TestOnly@123"));
        verifyNoInteractions(sessions);
    }
    @Test void legacyPasswordUpgradesOnLoginAndLogoutInvalidatesTheSession() {
        var user=new User();user.setId(42L);user.setPassword("TestOnly@123");user.setStatus("ACTIVE");
        when(users.findByEmail("legacy@example.test")).thenReturn(Optional.of(user));
        var result=auth.login("legacy@example.test","TestOnly@123");
        assertTrue(passwords.matches("TestOnly@123",user.getPassword()));verify(users).save(user);
        var captured=ArgumentCaptor.forClass(Session.class);verify(sessions).save(captured.capture());
        assertEquals(result.token(),captured.getValue().getToken());
        assertTrue(captured.getValue().getExpiresAt().isAfter(captured.getValue().getCreatedAt()));
        auth.logout(result.token());verify(sessionService).deleteByToken(result.token());
    }
}
