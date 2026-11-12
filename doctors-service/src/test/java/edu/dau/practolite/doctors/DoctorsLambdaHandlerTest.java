package edu.dau.practolite.doctors;

import static edu.dau.practolite.testsupport.LambdaInvoker.claims;
import static edu.dau.practolite.testsupport.LambdaInvoker.invoke;
import static org.assertj.core.api.Assertions.assertThat;

import edu.dau.practolite.testsupport.DynamoDbLocal;
import edu.dau.practolite.testsupport.LambdaInvoker.Response;
import edu.dau.practolite.testsupport.LocalEnvironment;
import edu.dau.practolite.testsupport.LocalTables;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/** Doctor flow through the real Lambda entry point and DynamoDB Local. */
class DoctorsLambdaHandlerTest {

    private static LocalTables.Names tables;
    private static StreamLambdaHandler handler;

    @BeforeAll
    static void setUp() {
        tables = LocalEnvironment.useDynamoDbLocal("lambda-doc");
        handler = new StreamLambdaHandler();
    }

    /** What registration (Users service) creates for a new doctor. */
    private static void registeredDoctor(String id, String name) {
        try (DynamoDbClient db = DynamoDbLocal.client()) {
            db.putItem(b -> b.tableName(tables.doctors()).item(Map.of(
                    "doctorId", AttributeValue.fromS(id),
                    "name", AttributeValue.fromS(name),
                    "nameLower", AttributeValue.fromS(name.toLowerCase()),
                    "verified", AttributeValue.fromBool(false),
                    "updatedAt", AttributeValue.fromS("2026-01-01T00:00:00Z"))));
        }
    }

    /** What admin verification (Users service) writes. */
    private static void verify(String id, String city, String specKey) {
        try (DynamoDbClient db = DynamoDbLocal.client()) {
            db.updateItem(b -> b.tableName(tables.doctors())
                    .key(Map.of("doctorId", AttributeValue.fromS(id)))
                    .updateExpression("SET verified = :t, searchCity = :c, specKey = :s")
                    .expressionAttributeValues(Map.of(":t", AttributeValue.fromBool(true),
                            ":c", AttributeValue.fromS(city), ":s", AttributeValue.fromS(specKey))));
        }
    }

    @Test
    void doctorProfileIsSearchableOnlyAfterVerification() throws Exception {
        String id = "doc-" + UUID.randomUUID();
        String city = "City" + UUID.randomUUID().toString().substring(0, 6);
        registeredDoctor(id, "Dr Kavya Rao");

        Response profile = invoke(handler, "PUT", "/doctors/me", null,
                "{\"speciality\":\"Dermatology\",\"city\":\"" + city + "\",\"fee\":600,\"experienceYears\":9}",
                claims(id, "[Doctor]"));
        assertThat(profile.status()).isEqualTo(200);
        assertThat(profile.body().path("verified").asBoolean()).isFalse();

        Response before = invoke(handler, "GET", "/doctors", "city=" + city, null, null);
        assertThat(before.status()).isEqualTo(200);
        assertThat(before.body().path("items")).isEmpty();
        assertThat(invoke(handler, "GET", "/doctors/" + id, null, null, null).status()).isEqualTo(404);

        verify(id, city.toLowerCase(), "dermatology#dr kavya rao");

        Response after = invoke(handler, "GET", "/doctors", "city=" + city + "&speciality=dermatology", null, null);
        assertThat(after.body().path("items").get(0).path("doctorId").asText()).isEqualTo(id);
        Response byName = invoke(handler, "GET", "/doctors", "city=" + city + "&name=kavya", null, null);
        assertThat(byName.body().path("items")).hasSize(1);
        assertThat(invoke(handler, "GET", "/doctors/" + id, null, null, null).status()).isEqualTo(200);
    }

    @Test
    void slotsCanBeCreatedListedAndDeleted() throws Exception {
        String id = "doc-" + UUID.randomUUID();
        registeredDoctor(id, "Dr Slot");
        verify(id, "slotcity", "general#dr slot");
        String tomorrow = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(1).toString();

        Response created = invoke(handler, "POST", "/doctors/me/slots", null,
                "{\"date\":\"" + tomorrow + "\",\"startTimes\":[\"10:00\",\"10:30\",\"10:00\"]}", claims(id, "[Doctor]"));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().path("created")).hasSize(2);

        Response again = invoke(handler, "POST", "/doctors/me/slots", null,
                "{\"date\":\"" + tomorrow + "\",\"startTimes\":[\"10:00\"]}", claims(id, "[Doctor]"));
        assertThat(again.body().path("skipped").get(0).path("reason").asText()).isEqualTo("DUPLICATE");

        Response list = invoke(handler, "GET", "/doctors/" + id + "/slots", null, null, null);
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.body().path("items")).hasSize(2);

        String slotStart = created.body().path("created").get(0).path("slotStart").asText();
        Response deleted = invoke(handler, "DELETE", "/doctors/me/slots/" + slotStart, null, null, claims(id, "[Doctor]"));
        assertThat(deleted.status()).isEqualTo(204);
        assertThat(invoke(handler, "GET", "/doctors/" + id + "/slots", null, null, null).body().path("items")).hasSize(1);
    }

    @Test
    void writeEndpointsNeedDoctorRole() throws Exception {
        String body = "{\"speciality\":\"ENT\",\"city\":\"Surat\",\"fee\":300}";
        assertThat(invoke(handler, "PUT", "/doctors/me", null, body, null).status()).isEqualTo(401);
        assertThat(invoke(handler, "PUT", "/doctors/me", null, body, claims("p1", "[Patient]")).status()).isEqualTo(403);
        Response invalid = invoke(handler, "PUT", "/doctors/me", null, "{\"city\":\"Surat\",\"fee\":-5}",
                claims("d-x", "[Doctor]"));
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.body().path("details").toString()).contains("fee", "speciality");
    }
}
