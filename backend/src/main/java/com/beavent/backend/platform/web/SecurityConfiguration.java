package com.beavent.backend.platform.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Baseline API security: stateless, no sessions or cookies, deny by default. Token authentication (JWT, docs/03
 * §2.5) is added with the identity module in Stage 0, step 0.7.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .authorizeHttpRequests(requests -> requests.requestMatchers("/actuator/health/**", "/actuator/info")
                        .permitAll()
                        // Payment provider webhooks: authenticated by their HMAC signature, not by a user token.
                        .requestMatchers("/v1/hooks/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated());
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
