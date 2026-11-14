package edu.dau.practolite.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * LOCAL DEVELOPMENT ONLY. Reads the caller from {@code X-Dev-User-Id} and {@code X-Dev-Groups}
 * headers, so you can run a service on your laptop against DynamoDB Local without Cognito.
 * Enabled only when DEV_HEADERS_AUTH=true, and {@link SecurityDefaults} refuses to start if that
 * is set inside Lambda.
 */
public class DevHeadersAuthenticationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String userId = request.getHeader("X-Dev-User-Id");
        if (userId != null && !userId.isBlank()
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            Set<Role> roles = GroupsClaimParser.parse(request.getHeader("X-Dev-Groups"));
            SecurityContextHolder.getContext().setAuthentication(
                    new CurrentUserAuthentication(new CurrentUser(userId, roles)));
        }
        chain.doFilter(request, response);
    }
}
