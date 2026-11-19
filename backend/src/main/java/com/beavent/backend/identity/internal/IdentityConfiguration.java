package com.beavent.backend.identity.internal;

import com.beavent.backend.platform.PublicEndpoints;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdentityProperties.class)
class IdentityConfiguration {

    @Bean
    PublicEndpoints identityPublicEndpoints() {
        return () -> List.of(
                "/v1/auth/otp/**",
                "/v1/auth/password",
                "/v1/auth/oauth/**",
                "/v1/auth/mfa/verify",
                "/v1/auth/refresh",
                "/.well-known/jwks.json");
    }
}
