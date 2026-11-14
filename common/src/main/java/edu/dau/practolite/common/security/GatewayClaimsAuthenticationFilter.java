package edu.dau.practolite.common.security;

import com.amazonaws.serverless.proxy.RequestReader;
import com.amazonaws.serverless.proxy.model.HttpApiV2AuthorizerMap;
import com.amazonaws.serverless.proxy.model.HttpApiV2ProxyRequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns the claims that API Gateway already verified into a Spring Security authentication.
 *
 * <p>Trust model: the HTTP API JWT authorizer checks the token's signature (Cognito JWKS),
 * issuer, audience/client_id and expiry <em>before</em> Lambda is invoked. The Lambda can only be
 * invoked by this API (resource policy), so the claims in the request context are trustworthy and the
 * function does not need to download keys and re-verify the token (which would slow cold starts).
 *
 * <p>Public routes have no authorizer, so they carry no claims and the request stays anonymous.
 * Spring Security then decides per route whether anonymous access is allowed.
 */
public class GatewayClaimsAuthenticationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Map<String, String> claims = jwtClaims(request);
        if (claims != null) {
            String sub = claims.get("sub");
            if (sub != null && !sub.isBlank()) {
                Set<Role> roles = GroupsClaimParser.parse(claims.get("cognito:groups"));
                SecurityContextHolder.getContext().setAuthentication(
                        new CurrentUserAuthentication(new CurrentUser(sub, roles)));
            }
        }
        chain.doFilter(request, response);
    }

    static Map<String, String> jwtClaims(HttpServletRequest request) {
        Object attr = request.getAttribute(RequestReader.HTTP_API_CONTEXT_PROPERTY);
        if (!(attr instanceof HttpApiV2ProxyRequestContext ctx)) {
            return null;
        }
        HttpApiV2AuthorizerMap authorizer = ctx.getAuthorizer();
        if (authorizer == null || !authorizer.isJwt() || authorizer.getJwtAuthorizer() == null) {
            return null;
        }
        return authorizer.getJwtAuthorizer().getClaims();
    }
}
