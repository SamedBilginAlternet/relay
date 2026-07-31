package com.relay.infrastructure.config;

import com.relay.infrastructure.crypto.AesGcmCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Encryption key and CORS. Both come from the environment. */
@Configuration
public class SecurityConfig implements WebMvcConfigurer {

    private final String allowedOrigins;

    public SecurityConfig(@Value("${app.cors.allowed-origins:http://localhost:5173}") String allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Bean
    public AesGcmCipher aesGcmCipher(@Value("${app.encryption.key:}") String key) {
        return new AesGcmCipher(key);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(allowedOrigins.split(","))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
