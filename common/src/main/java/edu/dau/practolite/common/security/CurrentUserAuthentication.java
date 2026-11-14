package edu.dau.practolite.common.security;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Spring Security authentication whose principal is a {@link CurrentUser}. */
public class CurrentUserAuthentication extends AbstractAuthenticationToken {

    private final CurrentUser user;

    public CurrentUserAuthentication(CurrentUser user) {
        super(user.roles().stream().map(r -> new SimpleGrantedAuthority(r.authority())).toList());
        this.user = user;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public CurrentUser getPrincipal() {
        return user;
    }

    @Override
    public String getName() {
        return user.userId();
    }

    /** Convenience for tests. */
    public static CurrentUserAuthentication of(String userId, Role... roles) {
        return new CurrentUserAuthentication(new CurrentUser(userId, java.util.Set.copyOf(List.of(roles))));
    }
}
