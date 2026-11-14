package edu.dau.practolite.doctors.config;

import edu.dau.practolite.common.security.SecurityDefaults;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** Search and doctor/slot reads are public; writing a profile or slots needs the Doctor role. */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityDefaults defaults) throws Exception {
        defaults.apply(http).authorizeHttpRequests(auth -> auth
                .requestMatchers(SecurityDefaults.INTERNAL_PATHS).permitAll()
                .requestMatchers(HttpMethod.PUT, "/doctors/me").hasRole("DOCTOR")
                .requestMatchers(HttpMethod.POST, "/doctors/me/slots").hasRole("DOCTOR")
                .requestMatchers(HttpMethod.DELETE, "/doctors/me/slots/*").hasRole("DOCTOR")
                .requestMatchers(HttpMethod.GET, "/doctors", "/doctors/*", "/doctors/*/slots").permitAll()
                .anyRequest().denyAll());
        return http.build();
    }
}
