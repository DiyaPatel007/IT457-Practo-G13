package edu.dau.practolite.users.cognito;

/**
 * The Cognito operations registration needs. It is an interface so unit tests can replace Cognito with a mock.
 */
public interface CognitoGateway {

    /** Creates a confirmed user with email as username. Throws 409 EMAIL_EXISTS if taken. */
    CreatedUser createUser(String email, String name);

    /** Sets a permanent password (no "change password" challenge). Throws 400 WEAK_PASSWORD if rejected. */
    void setPermanentPassword(String username, String password);

    void addToGroup(String username, String group);

    /** Used to undo a half-finished registration. */
    void deleteUser(String username);

    /**
     * @param username Cognito's internal username (for email-as-username pools, a UUID)
     * @param sub      the "sub" claim: our userId
     */
    record CreatedUser(String username, String sub) {
    }
}
