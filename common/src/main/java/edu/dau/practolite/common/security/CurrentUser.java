package edu.dau.practolite.common.security;

import java.util.Set;

/**
 * The authenticated caller. Controllers receive it with {@code @AuthenticationPrincipal CurrentUser user}.
 *
 * @param userId Cognito "sub" claim: stable, unique, never chosen by the user
 * @param roles  derived from the "cognito:groups" claim
 */
public record CurrentUser(String userId, Set<Role> roles) {

    public CurrentUser {
        roles = Set.copyOf(roles);
    }

    public boolean has(Role role) {
        return roles.contains(role);
    }
}
