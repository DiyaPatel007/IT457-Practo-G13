package edu.dau.practolite.appointments.config;

import edu.dau.practolite.common.security.SecurityDefaults;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Role rules. Ownership ("is this appointment yours?") cannot be expressed here because it depends on
 * data, so the services check it after loading the appointment.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityDefaults defaults) throws Exception {
        defaults.apply(http).authorizeHttpRequests(auth -> auth
                .requestMatchers(SecurityDefaults.INTERNAL_PATHS).permitAll()
                .requestMatchers(HttpMethod.POST, "/appointments").hasRole("PATIENT")
                .requestMatchers(HttpMethod.GET, "/appointments", "/appointments/*").hasAnyRole("PATIENT", "DOCTOR")
                .requestMatchers(HttpMethod.POST, "/appointments/*/cancel").hasAnyRole("PATIENT", "DOCTOR")
                .requestMatchers(HttpMethod.PATCH, "/appointments/*/status").hasRole("DOCTOR")
                .anyRequest().denyAll());
        return http.build();
    }
}
