package edu.dau.practolite.users;

import static edu.dau.practolite.testsupport.LambdaInvoker.claims;
import static edu.dau.practolite.testsupport.LambdaInvoker.invoke;
import static org.assertj.core.api.Assertions.assertThat;

import edu.dau.practolite.testsupport.DynamoDbLocal;
import edu.dau.practolite.testsupport.LambdaInvoker.Response;
import edu.dau.practolite.testsupport.LocalEnvironment;
import edu.dau.practolite.testsupport.LocalTables;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Users flow through the real Lambda entry point and DynamoDB Local. Cognito is replaced by the
 * in-memory FakeCognitoGateway (cognito.fake=true); the real gateway is covered by UserServiceTest mocks
 * and by the Postman run against AWS.
 */
class UsersLambdaHandlerTest {

    private static LocalTables.Names tables;
    private static StreamLambdaHandler handler;

    @BeforeAll
    static void setUp() {
        tables = LocalEnvironment.useDynamoDbLocal("lambda-users");
        System.setProperty("cognito.fake", "true");
        handler = new StreamLambdaHandler();
    }

    private static String registerBody(String email, String role) {
        return "{\"email\":\"" + email + "\",\"password\":\"Passw0rd1\",\"role\":\"" + role
                + "\",\"name\":\"Test User\",\"phone\":\"+919800000000\"}";
    }

    @Test
    void registerThenReadAndUpdateOwnProfile() throws Exception {
        String email = "p-" + UUID.randomUUID() + "@example.com";
        Response reg = invoke(handler, "POST", "/auth/register", null, registerBody(email, "PATIENT"), null);
        assertThat(reg.status()).isEqualTo(201);
        String userId = reg.body().path("userId").asText();

        Response dup = invoke(handler, "POST", "/auth/register", null, registerBody(email, "PATIENT"), null);
        assertThat(dup.status()).isEqualTo(409);
        assertThat(dup.body().path("error").asText()).isEqualTo("EMAIL_EXISTS");

        Response me = invoke(handler, "GET", "/users/me", null, null, claims(userId, "[Patient]"));
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.body().path("email").asText()).isEqualTo(email);

        Response patched = invoke(handler, "PATCH", "/users/me", null, "{\"name\":\"Renamed\"}",
                claims(userId, "[Patient]"));
        assertThat(patched.body().path("name").asText()).isEqualTo("Renamed");
        assertThat(patched.body().path("phone").asText()).isEqualTo("+919800000000");
    }

    @Test
    void doctorRegistrationThenAdminVerificationWritesSearchKeys() throws Exception {
        Response reg = invoke(handler, "POST", "/auth/register", null,
                registerBody("d-" + UUID.randomUUID() + "@example.com", "DOCTOR"), null);
        String doctorId = reg.body().path("userId").asText();

        Response incomplete = invoke(handler, "PATCH", "/admin/doctors/" + doctorId + "/verify", null,
                "{\"verified\":true}", claims("admin-1", "[Admin]"));
        assertThat(incomplete.status()).isEqualTo(409);
        assertThat(incomplete.body().path("error").asText()).isEqualTo("PROFILE_INCOMPLETE");

        try (DynamoDbClient db = DynamoDbLocal.client()) { // what PUT /doctors/me (Doctors service) would write
            db.updateItem(b -> b.tableName(tables.doctors()).key(Map.of("doctorId", AttributeValue.fromS(doctorId)))
                    .updateExpression("SET city = :c, speciality = :s")
                    .expressionAttributeValues(Map.of(":c", AttributeValue.fromS("Vadodara"),
                            ":s", AttributeValue.fromS("ENT"))));
        }

        Response notAdmin = invoke(handler, "PATCH", "/admin/doctors/" + doctorId + "/verify", null,
                "{\"verified\":true}", claims(doctorId, "[Doctor]"));
        assertThat(notAdmin.status()).isEqualTo(403);

        Response ok = invoke(handler, "PATCH", "/admin/doctors/" + doctorId + "/verify", null,
                "{\"verified\":true}", claims("admin-1", "[Admin]"));
        assertThat(ok.status()).isEqualTo(200);

        try (DynamoDbClient db = DynamoDbLocal.client()) {
            Map<String, AttributeValue> item = db.getItem(b -> b.tableName(tables.doctors())
                    .key(Map.of("doctorId", AttributeValue.fromS(doctorId)))).item();
            assertThat(item.get("searchCity").s()).isEqualTo("vadodara");
            assertThat(item.get("specKey").s()).isEqualTo("ent#test user");
        }
    }

    @Test
    void registrationValidationAndAdminRoleRejection() throws Exception {
        Response bad = invoke(handler, "POST", "/auth/register", null,
                "{\"email\":\"not-an-email\",\"password\":\"x\",\"role\":\"PATIENT\",\"name\":\"A\"}", null);
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.body().path("details").toString()).contains("email", "password", "name");

        Response admin = invoke(handler, "POST", "/auth/register", null,
                registerBody("a-" + UUID.randomUUID() + "@example.com", "ADMIN"), null);
        assertThat(admin.status()).isEqualTo(400);
        assertThat(admin.body().path("error").asText()).isEqualTo("ROLE_NOT_ALLOWED");

        assertThat(invoke(handler, "GET", "/users/me", null, null, null).status()).isEqualTo(401);
    }
}
