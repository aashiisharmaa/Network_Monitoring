package com.network.monitoring.identity.service;
import com.network.monitoring.identity.entity.User;
import com.network.monitoring.identity.repository.UserRepository;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class UserManagementServiceTest {
    @Test void administratorCanCreateCompanyScopedAdminWithHashedPassword() {
        var users=mock(UserRepository.class);var actor=mock(RoleAuthorizationService.class);
        when(users.findByEmail(anyString())).thenReturn(Optional.empty());
        when(users.save(any())).thenAnswer(call->{User u=call.getArgument(0);u.setId(99L);return u;});
        var service=new UserManagementService(users,new BCryptPasswordEncoder(4),actor);
        var result=service.createUser(Map.of("email","demo@example.test","password","TestOnly@123","role","ADMIN","companyId",7L));
        assertEquals("ADMIN",result.get("role"));assertEquals(7L,result.get("companyId"));assertEquals("ACTIVE",result.get("status"));
        var captor=org.mockito.ArgumentCaptor.forClass(User.class);verify(users).save(captor.capture());
        assertNotEquals("TestOnly@123",captor.getValue().getPassword());verify(actor).requireSuperAdmin();
    }
    @Test void invalidRoleAndUnauthorizedActorCannotCreateAccounts() {
        var users=mock(UserRepository.class);var actor=mock(RoleAuthorizationService.class);
        var service=new UserManagementService(users,new BCryptPasswordEncoder(4),actor);
        assertThrows(IllegalArgumentException.class,()->service.createUser(Map.of("email","demo@example.test","password","TestOnly@123","role","ROOT")));
        doThrow(new IllegalArgumentException("Forbidden")).when(actor).requireSuperAdmin();
        assertThrows(IllegalArgumentException.class,()->service.createUser(Map.of("email","demo@example.test","password","TestOnly@123","role","ADMIN")));
        verify(users,never()).save(any());
    }
}
