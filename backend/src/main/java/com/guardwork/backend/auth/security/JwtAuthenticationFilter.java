package com.guardwork.backend.auth.security;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.guardwork.backend.auth.service.JwtService;
import com.guardwork.backend.auth.Portal;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private final JwtService jwtService;
    private final RestAccessDeniedHandler accessDeniedHandler;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;

    public JwtAuthenticationFilter(JwtService jwtService, RestAccessDeniedHandler accessDeniedHandler,
                                   RestAuthenticationEntryPoint authenticationEntryPoint) {
        this.jwtService = jwtService;
        this.accessDeniedHandler = accessDeniedHandler;
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");
        Portal tokenPortal = null;

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String jwt = authHeader.substring(7).trim();
            try {
                Claims claims = jwtService.parseAndValidateToken(jwt);
                Long userId = Long.parseLong(claims.getSubject());
                String username = claims.get("username", String.class);
                String email = claims.get("email", String.class);
                String role = claims.get("role", String.class);
                String portal = claims.get("portal", String.class);
                tokenPortal = portal == null ? Portal.fromRole(role) : Portal.parse(portal);

                UserPrincipal principal = new UserPrincipal(userId, username, email, role);
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities()
                );
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (Exception ignored) {
                tokenPortal = null;
                SecurityContextHolder.clearContext();
                log.debug("event=auth_access_token outcome=rejected");
                // If token is invalid or expired, SecurityContext remains unauthenticated.
                // Protected endpoints will be caught by AuthenticationEntryPoint.
            }
        }

        // Context cannot grant authentication: invalid tokens still reach the 401 boundary.
        if (tokenPortal != null && request.getRequestURI().startsWith(request.getContextPath() + "/api/")) {
            try {
                Portal requestPortal = Portal.fromRequest(request);
                if (requestPortal != null && requestPortal != tokenPortal) {
                    String path = request.getRequestURI().substring(request.getContextPath().length());
                    // Refresh/logout are session-credential failures even when Bearer is also sent.
                    if ("POST".equals(request.getMethod())
                            && ("/api/auth/refresh".equals(path) || "/api/auth/logout".equals(path))) {
                        authenticationEntryPoint.commence(request, response,
                                new BadCredentialsException("Token belongs to another portal"));
                        return;
                    }
                    throw new AccessDeniedException("Token belongs to another portal");
                }
            } catch (org.springframework.web.server.ResponseStatusException | AccessDeniedException exception) {
                accessDeniedHandler.handle(request, response, new AccessDeniedException("Invalid portal context"));
                return;
            }
        }

        filterChain.doFilter(request, response);
    }
}
