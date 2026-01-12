package io.yamsergey.adt.debug.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

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

        // If no token is set (MCP mode with --no-auth), permit all requests
        boolean noAuth = accessToken == null || accessToken.isEmpty();

        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        if (noAuth) {
            // No authentication required
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        } else {
            // Token-based authentication
            http
                .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/health").permitAll()  // Health check is public
                    .requestMatchers("/index.html", "/", "/api/**").authenticated()  // UI and API require auth
                    .anyRequest().authenticated()
                )
                .addFilterBefore(new TokenAuthFilter(accessToken), UsernamePasswordAuthenticationFilter.class);
        }

        return http.build();
    }

    /**
     * Filter that validates the access token from Authorization header, query param, or session cookie.
     */
    private static class TokenAuthFilter extends OncePerRequestFilter {

        private static final String SESSION_COOKIE_NAME = "debug_session";
        private final String validToken;
        private final String sessionValue;

        public TokenAuthFilter(String validToken) {
            this.validToken = validToken;
            // Create a hash of the token to use as session value (don't expose actual token in cookie)
            this.sessionValue = hashToken(validToken);
        }

        private static String hashToken(String token) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] hash = md.digest(token.getBytes());
                return Base64.getUrlEncoder().withoutPadding().encodeToString(hash).substring(0, 32);
            } catch (NoSuchAlgorithmException e) {
                // Fallback - shouldn't happen as SHA-256 is always available
                return token.substring(0, Math.min(32, token.length()));
            }
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

            // Check Authorization header (for API clients)
            String authHeader = request.getHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7);
                if (token.equals(validToken)) {
                    setAuthenticated(token);
                    filterChain.doFilter(request, response);
                    return;
                }
            }

            // Check session cookie (for browser UI navigation)
            Cookie[] cookies = request.getCookies();
            if (cookies != null) {
                for (Cookie cookie : cookies) {
                    if (SESSION_COOKIE_NAME.equals(cookie.getName()) && sessionValue.equals(cookie.getValue())) {
                        setAuthenticated(validToken);
                        filterChain.doFilter(request, response);
                        return;
                    }
                }
            }

            // Check query param (for initial browser access, then set cookie)
            String tokenParam = request.getParameter("token");
            if (tokenParam != null && tokenParam.equals(validToken)) {
                setAuthenticated(tokenParam);
                // Set session cookie for subsequent requests
                Cookie sessionCookie = new Cookie(SESSION_COOKIE_NAME, sessionValue);
                sessionCookie.setPath("/");
                sessionCookie.setHttpOnly(true);
                sessionCookie.setMaxAge(3600); // 1 hour
                response.addCookie(sessionCookie);
                filterChain.doFilter(request, response);
                return;
            }

            // No valid token found - show login page for UI routes
            String uri = request.getRequestURI();
            if (uri.equals("/") || uri.equals("/index.html") || uri.startsWith("/api/")) {
                response.setContentType("text/html");
                response.getWriter().write(getLoginPage());
                return;
            }

            // API routes get JSON error
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"Missing or invalid access token\"}");
        }

        private void setAuthenticated(String token) {
            org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(new TokenAuthentication(token));
        }

        private String getLoginPage() {
            return """
                <!DOCTYPE html>
                <html>
                <head>
                    <title>Debug Server - Login</title>
                    <style>
                        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                               background: #1a1a2e; color: #eee; display: flex; justify-content: center;
                               align-items: center; min-height: 100vh; margin: 0; }
                        .login-box { background: #16213e; padding: 40px; border-radius: 8px; width: 400px; }
                        h1 { margin: 0 0 20px 0; font-size: 24px; }
                        p { color: #888; margin-bottom: 20px; }
                        input { width: 100%; padding: 12px; border: 1px solid #333; border-radius: 4px;
                                background: #0f0f23; color: #eee; font-size: 14px; box-sizing: border-box; }
                        button { width: 100%; padding: 12px; background: #4a90d9; border: none; border-radius: 4px;
                                 color: white; font-size: 14px; cursor: pointer; margin-top: 15px; }
                        button:hover { background: #357abd; }
                        .error { color: #ff6b6b; margin-top: 10px; display: none; }
                    </style>
                </head>
                <body>
                    <div class="login-box">
                        <h1>Debug Server</h1>
                        <p>Enter the access token shown in your terminal to continue.</p>
                        <form onsubmit="return login()">
                            <input type="text" id="token" placeholder="sk_dbg_..." autocomplete="off" autofocus>
                            <button type="submit">Login</button>
                        </form>
                        <div class="error" id="error">Invalid token. Please try again.</div>
                    </div>
                    <script>
                        function login() {
                            const token = document.getElementById('token').value.trim();
                            if (token) {
                                window.location.href = '/index.html?token=' + encodeURIComponent(token);
                            }
                            return false;
                        }
                    </script>
                </body>
                </html>
                """;
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
