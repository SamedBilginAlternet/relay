package com.relay.infrastructure.config;

import com.relay.application.auth.AuthService;
import com.relay.application.port.Clock;
import com.relay.application.port.PasswordHasher;
import com.relay.application.port.SessionRepository;
import com.relay.application.port.UserRepository;
import com.relay.infrastructure.auth.AuthFilter;
import com.relay.infrastructure.auth.BCryptPasswordHasher;
import com.relay.infrastructure.auth.SessionCookies;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Sign-in wiring. Kept apart from {@link ApplicationConfig} so the orchestrator wiring
 * stays readable, and deliberately built from plain beans instead of the Spring Security
 * starter — that starter installs its own filter chain and would 401 the existing
 * endpoints before {@link AuthFilter} ever ran.
 */
@Configuration
public class AuthConfig {

    @Bean
    public PasswordHasher passwordHasher() {
        return new BCryptPasswordHasher();
    }

    @Bean
    public AuthService authService(UserRepository users, SessionRepository sessions,
                                   PasswordHasher passwordHasher, Clock clock) {
        return new AuthService(users, sessions, passwordHasher, clock);
    }

    /**
     * {@code Secure} is on by default; the browser makes an exception for localhost, so
     * local development needs no flag. Set {@code AUTH_COOKIE_SECURE=false} only when
     * something is served over plain HTTP on a real hostname.
     */
    @Bean
    public SessionCookies sessionCookies(@Value("${app.auth.cookie.secure:true}") boolean secure) {
        return new SessionCookies(secure);
    }

    @Bean
    public FilterRegistrationBean<AuthFilter> authFilterRegistration(
            AuthService authService,
            @Value("${app.auth.enabled:true}") boolean enabled) {
        FilterRegistrationBean<AuthFilter> registration =
                new FilterRegistrationBean<>(new AuthFilter(authService, enabled));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 50);
        registration.setName("relayAuthFilter");
        return registration;
    }
}
