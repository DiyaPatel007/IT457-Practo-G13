package edu.dau.practolite.users.cognito;

import edu.dau.practolite.common.web.ApiException;
import edu.dau.practolite.users.config.CognitoConfig.CognitoProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InvalidPasswordException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.MessageActionType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UsernameExistsException;

/**
 * Real Cognito implementation using admin APIs (needs IAM permissions on this user pool only).
 * AdminCreateUser with MessageAction=SUPPRESS creates the user without sending an invitation email;
 * email_verified=true is acceptable here because this is a test system with synthetic users.
 */
@Component
@ConditionalOnProperty(name = "cognito.fake", havingValue = "false", matchIfMissing = true)
public class AwsCognitoGateway implements CognitoGateway {

    private final CognitoIdentityProviderClient cognito;
    private final String userPoolId;

    public AwsCognitoGateway(CognitoIdentityProviderClient cognito, CognitoProperties props) {
        this.cognito = cognito;
        this.userPoolId = props.userPoolId();
    }

    @Override
    public CreatedUser createUser(String email, String name) {
        try {
            AdminCreateUserResponse res = cognito.adminCreateUser(b -> b
                    .userPoolId(userPoolId)
                    .username(email)
                    .messageAction(MessageActionType.SUPPRESS)
                    .userAttributes(
                            AttributeType.builder().name("email").value(email).build(),
                            AttributeType.builder().name("email_verified").value("true").build(),
                            AttributeType.builder().name("name").value(name).build()));
            String sub = res.user().attributes().stream()
                    .filter(a -> "sub".equals(a.name()))
                    .map(AttributeType::value)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Cognito did not return a sub"));
            return new CreatedUser(res.user().username(), sub);
        } catch (UsernameExistsException e) {
            throw ApiException.conflict("EMAIL_EXISTS", "An account with this email already exists");
        }
    }

    @Override
    public void setPermanentPassword(String username, String password) {
        try {
            cognito.adminSetUserPassword(b -> b.userPoolId(userPoolId).username(username)
                    .password(password).permanent(true));
        } catch (InvalidPasswordException e) {
            throw ApiException.badRequest("WEAK_PASSWORD",
                    "Password must have at least 8 characters with upper case, lower case and a number");
        }
    }

    @Override
    public void addToGroup(String username, String group) {
        cognito.adminAddUserToGroup(b -> b.userPoolId(userPoolId).username(username).groupName(group));
    }

    @Override
    public void deleteUser(String username) {
        cognito.adminDeleteUser(b -> b.userPoolId(userPoolId).username(username));
    }
}
