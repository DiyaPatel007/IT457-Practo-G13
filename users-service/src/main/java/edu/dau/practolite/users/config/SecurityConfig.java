package edu.dau.practolite.users.config;

import edu.dau.practolite.common.security.SecurityDefaults;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Route rules for the Users service. API Gateway already rejects requests without a valid token
 * on protected routes (401); these rules add the role checks (403) that API Gateway cannot do.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityDefaults defaults) throws Exception {
        defaults.apply(http).authorizeHttpRequests(auth -> auth
                .requestMatchers(SecurityDefaults.INTERNAL_PATHS).permitAll()
                .requestMatchers(HttpMethod.POST, "/auth/register").permitAll()
                .requestMatchers(HttpMethod.GET, "/users/me").hasAnyRole("PATIENT", "DOCTOR")
                .requestMatchers(HttpMethod.PATCH, "/users/me").hasAnyRole("PATIENT", "DOCTOR")
                .requestMatchers(HttpMethod.PATCH, "/admin/doctors/*/verify").hasRole("ADMIN")
                .anyRequest().denyAll());
        return http.build();
    }
}
