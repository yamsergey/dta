package io.yamsergey.adt.debug.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import io.yamsergey.adt.debug.DebugServerApplication;

import java.io.IOException;

/**
 * Security configuration with token-based authentication.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // Get token from static holder (set before context starts)
        String accessToken = DebugServerApplication.getAccessToken();

        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/health").permitAll()  // Health check is public
                .anyRequest().authenticated()
            )
            .addFilterBefore(new TokenAuthFilter(accessToken), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Filter that validates the access token from Authorization header or query param.
     */
    private static class TokenAuthFilter extends OncePerRequestFilter {

        private final String validToken;

        public TokenAuthFilter(String validToken) {
            this.validToken = validToken;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            // Skip auth for health endpoint
            if (request.getRequestURI().equals("/health")) {
                filterChain.doFilter(request, response);
                return;
            }

            // Check Authorization header
            String authHeader = request.getHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7);
                if (token.equals(validToken)) {
                    // Set authenticated user in security context
                    org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .setAuthentication(new TokenAuthentication(token));
                    filterChain.doFilter(request, response);
                    return;
                }
            }

            // Check query param fallback
            String tokenParam = request.getParameter("token");
            if (tokenParam != null && tokenParam.equals(validToken)) {
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                    .setAuthentication(new TokenAuthentication(tokenParam));
                filterChain.doFilter(request, response);
                return;
            }

            // No valid token found
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"Missing or invalid access token\"}");
        }
    }

    /**
     * Simple authentication token.
     */
    private static class TokenAuthentication implements org.springframework.security.core.Authentication {

        private final String token;
        private boolean authenticated = true;

        public TokenAuthentication(String token) {
            this.token = token;
        }

        @Override
        public java.util.Collection<? extends org.springframework.security.core.GrantedAuthority> getAuthorities() {
            return java.util.Collections.emptyList();
        }

        @Override
        public Object getCredentials() {
            return token;
        }

        @Override
        public Object getDetails() {
            return null;
        }

        @Override
        public Object getPrincipal() {
            return "debug-user";
        }

        @Override
        public boolean isAuthenticated() {
            return authenticated;
        }

        @Override
        public void setAuthenticated(boolean isAuthenticated) throws IllegalArgumentException {
            this.authenticated = isAuthenticated;
        }

        @Override
        public String getName() {
            return "debug-user";
        }
    }
}
