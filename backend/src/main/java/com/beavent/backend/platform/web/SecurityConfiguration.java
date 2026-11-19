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
        http.csrf(csrf -> csrf.disable())
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

    /** No built-in users: suppresses Spring Boot's generated development password. */
    @Bean
    UserDetailsService noUsers() {
        return username -> {
            throw new UsernameNotFoundException(username);
        };
    }
}
