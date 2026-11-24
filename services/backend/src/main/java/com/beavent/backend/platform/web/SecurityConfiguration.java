package com.beavent.backend.platform.web;

import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.PublicEndpoints;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * API security: stateless bearer JWTs (keys from the identity module), no sessions or cookies, deny by default.
 * Modules declare their public endpoints through {@link PublicEndpoints} beans.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, List<PublicEndpoints> modules, ProblemResponses problems)
            throws Exception {
        List<String> open = new ArrayList<>(List.of(
                "/actuator/health/**",
                "/actuator/info",
                ApiSpecController.PATH,
                // Payment provider webhooks: authenticated by their HMAC signature, not by a user token.
                "/v1/hooks/**"));
        modules.forEach(module -> open.addAll(module.patterns()));
        http.cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .authorizeHttpRequests(requests -> requests.requestMatchers(open.toArray(String[]::new))
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(
                                (request, response, e) -> problems.write(response, ProblemCode.UNAUTHENTICATED))
                        .accessDeniedHandler((request, response, e) -> problems.write(response, ProblemCode.FORBIDDEN)))
                .exceptionHandling(handling -> handling.authenticationEntryPoint(
                                (request, response, e) -> problems.write(response, ProblemCode.UNAUTHENTICATED))
                        .accessDeniedHandler((request, response, e) -> problems.write(response, ProblemCode.FORBIDDEN)))
                .addFilterAfter(new AuthenticatedRequestContextFilter(), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Browsers may call the API directly only from the listed origins ({@code beavent.web.allowed-origins}; none by
     * default). Tokens travel in the Authorization header, never in cookies, so credentials are not allowed.
     */
    @Bean
    org.springframework.web.cors.CorsConfigurationSource corsConfigurationSource(
            @org.springframework.beans.factory.annotation.Value("${beavent.web.allowed-origins:}")
                    List<String> origins) {
        var config = new org.springframework.web.cors.CorsConfiguration();
        config.setAllowedOrigins(origins.stream().filter(o -> !o.isBlank()).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "Accept",
                "Accept-Language",
                "X-Request-Id",
                "If-Match",
                "Idempotency-Key"));
        config.setExposedHeaders(List.of("ETag", "X-Request-Id", "Retry-After"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);
        var source = new org.springframework.web.cors.UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /** No built-in users: suppresses Spring Boot's generated development password. */
    @Bean
    UserDetailsService noUsers() {
        return username -> {
            throw new UsernameNotFoundException(username);
        };
    }
}
