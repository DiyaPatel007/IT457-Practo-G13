package edu.dau.practolite.users.cognito;

import edu.dau.practolite.common.config.LambdaEnvironment;
import edu.dau.practolite.common.web.ApiException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * LOCAL DEVELOPMENT ONLY (COGNITO_FAKE=true): an in-memory stand-in for Cognito, so registration
 * can be tried on a laptop with DynamoDB Local. Refuses to start inside Lambda.
 */
@Component
@ConditionalOnProperty(name = "cognito.fake", havingValue = "true")
public class FakeCognitoGateway implements CognitoGateway {

    private final Map<String, String> subByEmail = new ConcurrentHashMap<>();

    public FakeCognitoGateway() {
        if (LambdaEnvironment.isLambda()) {
            throw new IllegalStateException("COGNITO_FAKE must never be enabled inside AWS Lambda");
        }
    }

    @Override
    public CreatedUser createUser(String email, String name) {
        String sub = UUID.randomUUID().toString();
        if (subByEmail.putIfAbsent(email, sub) != null) {
            throw ApiException.conflict("EMAIL_EXISTS", "An account with this email already exists");
        }
        return new CreatedUser(email, sub);
    }

    @Override
    public void setPermanentPassword(String username, String password) {
        boolean strong = password.length() >= 8 && password.chars().anyMatch(Character::isUpperCase)
                && password.chars().anyMatch(Character::isLowerCase) && password.chars().anyMatch(Character::isDigit);
        if (!strong) {
            throw ApiException.badRequest("WEAK_PASSWORD",
                    "Password must have at least 8 characters with upper case, lower case and a number");
        }
    }

    @Override
    public void addToGroup(String username, String group) {
        // Nothing to store: locally, groups are sent in the X-Dev-Groups header.
    }

    @Override
    public void deleteUser(String username) {
        subByEmail.remove(username);
    }
}
