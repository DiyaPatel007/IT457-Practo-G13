package edu.dau.practolite.common.security;

import java.util.Optional;

/** Application roles. Each maps to one Cognito group and one Spring Security authority. */
public enum Role {
    PATIENT("Patient"),
    DOCTOR("Doctor"),
    ADMIN("Admin");

    private final String cognitoGroup;

    Role(String cognitoGroup) {
        this.cognitoGroup = cognitoGroup;
    }

    public String cognitoGroup() {
        return cognitoGroup;
    }

    /** Spring Security authority, e.g. ROLE_PATIENT (used by hasRole("PATIENT")). */
    public String authority() {
        return "ROLE_" + name();
    }

    public static Optional<Role> fromCognitoGroup(String group) {
        for (Role r : values()) {
            if (r.cognitoGroup.equalsIgnoreCase(group)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }
}
