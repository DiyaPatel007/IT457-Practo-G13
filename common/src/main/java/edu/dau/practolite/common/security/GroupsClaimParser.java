package edu.dau.practolite.common.security;

import java.util.EnumSet;
import java.util.Set;

/**
 * Parses the "cognito:groups" claim.
 *
 * <p>An API Gateway HTTP API JWT authorizer passes claims to Lambda as strings, and array claims
 * arrive flattened, for example {@code "[Patient]"} or {@code "[Admin Doctor]"}. This parser also
 * accepts JSON-style ({@code ["Patient","Doctor"]}) and comma-separated values. Unknown groups are ignored.
 */
public final class GroupsClaimParser {

    private GroupsClaimParser() {
    }

    public static Set<Role> parse(String claim) {
        Set<Role> roles = EnumSet.noneOf(Role.class);
        if (claim == null || claim.isBlank()) {
            return roles;
        }
        String cleaned = claim.replace("[", " ").replace("]", " ").replace("\"", " ").replace(",", " ");
        for (String token : cleaned.trim().split("\\s+")) {
            if (!token.isEmpty()) {
                Role.fromCognitoGroup(token).ifPresent(roles::add);
            }
        }
        return roles;
    }
}
