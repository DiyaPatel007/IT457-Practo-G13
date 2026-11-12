package edu.dau.practolite.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.dau.practolite.common.config.AppProperties;
import edu.dau.practolite.common.config.LambdaEnvironment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.stereotype.Component;

/**
 * Security settings shared by all three services. Each service's SecurityConfig calls
 * {@link #apply(HttpSecurity)} and then adds its own route rules.
 *
 * <ul>
 *   <li>Stateless: no sessions, no cookies, so CSRF protection is not needed.</li>
 *   <li>No form login or HTTP Basic: identity comes only from the gateway-verified JWT.</li>
 *   <li>401 and 403 are returned as JSON.</li>
 * </ul>
 */
@Component
public class SecurityDefaults {

    /** Paths every service must permit: Spring's error page and the SnapStart priming target. */
    public static final String[] INTERNAL_PATHS = {"/error", "/internal/prime"};

    private final ObjectMapper mapper;
    private final boolean devHeaders;

    public SecurityDefaults(ObjectMapper mapper, AppProperties props) {
        this.mapper = mapper;
        this.devHeaders = props.security() != null && props.security().devHeaders();
        if (devHeaders && LambdaEnvironment.isLambda()) {
            throw new IllegalStateException("DEV_HEADERS_AUTH must never be enabled inside AWS Lambda");
        }
    }

    public HttpSecurity apply(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(JsonSecurityErrorHandlers.unauthorized(mapper))
                        .accessDeniedHandler(JsonSecurityErrorHandlers.forbidden(mapper)))
                .addFilterBefore(new GatewayClaimsAuthenticationFilter(), AnonymousAuthenticationFilter.class);
        if (devHeaders) {
            http.addFilterBefore(new DevHeadersAuthenticationFilter(), AnonymousAuthenticationFilter.class);
        }
        return http;
    }
}
