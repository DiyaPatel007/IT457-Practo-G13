package edu.dau.practolite.doctors;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

/**
 * DoctorsFunction: PUT /doctors/me, GET /doctors, GET /doctors/{id}, POST /doctors/me/slots,
 * GET /doctors/{id}/slots, DELETE /doctors/me/slots/{slotStart}.
 */
@SpringBootApplication(scanBasePackages = "edu.dau.practolite", exclude = UserDetailsServiceAutoConfiguration.class)
public class DoctorsApplication {

    public static void main(String[] args) {
        SpringApplication.run(DoctorsApplication.class, args);
    }
}
