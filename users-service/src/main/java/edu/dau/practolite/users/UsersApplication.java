package edu.dau.practolite.users;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * UsersFunction: POST /auth/register, GET and PATCH /users/me, PATCH /admin/doctors/{id}/verify.
 *
 * <p>scanBasePackages includes the common module (security, errors, DynamoDB client, metrics).
 * UserDetailsServiceAutoConfiguration is excluded because there are no usernames or passwords
 * in this app: identity comes from Cognito tokens checked by API Gateway.
 */
@SpringBootApplication(scanBasePackages = "edu.dau.practolite", exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class UsersApplication {

    public static void main(String[] args) {
        SpringApplication.run(UsersApplication.class, args);
    }
}
