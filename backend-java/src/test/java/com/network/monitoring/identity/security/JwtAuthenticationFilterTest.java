package com.network.monitoring.identity.security;

import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.network.monitoring.identity.service.SessionService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtAuthenticationFilterTest {
    private final JwtService jwt=mock(JwtService.class);
    private final SessionService sessions=mock(SessionService.class);
    private final JwtAuthenticationFilter filter=new JwtAuthenticationFilter(jwt,sessions);
    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    @Test void anonymousPublicReadsStillWorkWithoutTokenParsing() throws Exception {
        var chain=mock(FilterChain.class);var request=new MockHttpServletRequest("GET","/api/v1/companies");var response=new MockHttpServletResponse();
        filter.doFilter(request,response,chain);verify(chain).doFilter(request,response);verifyNoInteractions(jwt,sessions);
    }
    @Test void bearerOnPublicReadPopulatesPrincipalInsteadOfBeingSilentlyIgnored() throws Exception {
        var decoded=mock(DecodedJWT.class);var claim=mock(Claim.class);
        when(jwt.verifyToken("valid")).thenReturn(decoded);when(decoded.getClaim("userId")).thenReturn(claim);when(claim.asLong()).thenReturn(9L);
        when(sessions.isTokenActive("valid")).thenReturn(true);
        var chain=mock(FilterChain.class);var request=new MockHttpServletRequest("GET","/api/v1/companies");request.addHeader("Authorization","Bearer valid");var response=new MockHttpServletResponse();
        filter.doFilter(request,response,chain);
        assertEquals(9L,SecurityContextHolder.getContext().getAuthentication().getPrincipal());verify(chain).doFilter(request,response);
    }
    @Test void invalidSuppliedTokenCannotFallThroughAsAnAuthenticatedRead() throws Exception {
        var chain=mock(FilterChain.class);var request=new MockHttpServletRequest("GET","/api/v1/companies");request.addHeader("Authorization","Bearer expired");var response=new MockHttpServletResponse();
        filter.doFilter(request,response,chain);assertEquals(401,response.getStatus());verifyNoInteractions(chain);
    }
}
