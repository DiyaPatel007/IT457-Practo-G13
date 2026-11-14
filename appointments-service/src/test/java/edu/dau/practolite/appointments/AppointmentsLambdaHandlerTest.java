package edu.dau.practolite.appointments;

import static edu.dau.practolite.testsupport.LambdaInvoker.claims;
import static edu.dau.practolite.testsupport.LambdaInvoker.invoke;
import static org.assertj.core.api.Assertions.assertThat;

import edu.dau.practolite.common.model.TimeSupport;
import edu.dau.practolite.testsupport.DynamoDbLocal;
import edu.dau.practolite.testsupport.LambdaInvoker.Response;
import edu.dau.practolite.testsupport.LocalEnvironment;
import edu.dau.practolite.testsupport.LocalTables;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * End-to-end through the real Lambda entry point: API Gateway HTTP API event JSON
 * -> StreamLambdaHandler -> aws-serverless-java-container -> Spring Security -> controller -> DynamoDB Local.
 * The only thing not real is API Gateway itself, whose JWT claims are put into the event by the test.
 */
class AppointmentsLambdaHandlerTest {

    private static LocalTables.Names tables;
    private static StreamLambdaHandler handler;
    private static final String DOCTOR = "doc-" + UUID.randomUUID();
    private static final String PATIENT_A = "pat-a-" + UUID.randomUUID();
    private static final String PATIENT_B = "pat-b-" + UUID.randomUUID();

    @BeforeAll
    static void setUp() {
        tables = LocalEnvironment.useDynamoDbLocal("lambda-appt");
        try (DynamoDbClient db = DynamoDbLocal.client()) {
            db.putItem(b -> b.tableName(tables.doctors()).item(Map.of(
                    "doctorId", AttributeValue.fromS(DOCTOR),
                    "name", AttributeValue.fromS("Dr Asha Mehta"),
                    "verified", AttributeValue.fromBool(true))));
            db.putItem(b -> b.tableName(tables.users()).item(Map.of(
                    "userId", AttributeValue.fromS(PATIENT_A),
                    "name", AttributeValue.fromS("Ravi Patel"))));
        }
        handler = new StreamLambdaHandler(); // static block starts Spring Boot now, after the properties are set
    }

    private static String createSlot(Instant start) {
        String key = TimeSupport.toKey(start);
        try (DynamoDbClient db = DynamoDbLocal.client()) {
            db.putItem(b -> b.tableName(tables.slots()).item(Map.of(
                    "doctorId", AttributeValue.fromS(DOCTOR),
                    "slotStart", AttributeValue.fromS(key),
                    "status", AttributeValue.fromS("AVAILABLE"))));
        }
        return key;
    }

    private static String bookBody(String slot) {
        return "{\"doctorId\":\"" + DOCTOR + "\",\"slotStart\":\"" + slot + "\",\"reason\":\"Fever\"}";
    }

    @Test
    void bookThenSecondPatientGetsConflictThenCancelFreesSlot() throws Exception {
        String slot = createSlot(Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS));

        Response booked = invoke(handler, "POST", "/appointments", null, bookBody(slot), claims(PATIENT_A, "[Patient]"));
        assertThat(booked.status()).isEqualTo(201);
        assertThat(booked.body().path("status").asText()).isEqualTo("BOOKED");
        assertThat(booked.body().path("doctorName").asText()).isEqualTo("Dr Asha Mehta");
        assertThat(booked.body().path("patientName").asText()).isEqualTo("Ravi Patel");
        assertThat(booked.headers()).containsKey("X-Cold-Start");
        String appointmentId = booked.body().path("appointmentId").asText();

        Response second = invoke(handler, "POST", "/appointments", null, bookBody(slot), claims(PATIENT_B, "[Patient]"));
        assertThat(second.status()).isEqualTo(409);
        assertThat(second.body().path("error").asText()).isEqualTo("SLOT_TAKEN");

        Response doctorList = invoke(handler, "GET", "/appointments", null, null, claims(DOCTOR, "[Doctor]"));
        assertThat(doctorList.status()).isEqualTo(200);
        assertThat(doctorList.body().path("items").toString()).contains(appointmentId);

        Response strangerCancel = invoke(handler, "POST", "/appointments/" + appointmentId + "/cancel", null, null,
                claims(PATIENT_B, "[Patient]"));
        assertThat(strangerCancel.status()).isEqualTo(403);

        Response cancelled = invoke(handler, "POST", "/appointments/" + appointmentId + "/cancel", null, null,
                claims(PATIENT_A, "[Patient]"));
        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(cancelled.body().path("status").asText()).isEqualTo("CANCELLED");

        Response rebooked = invoke(handler, "POST", "/appointments", null, bookBody(slot), claims(PATIENT_B, "[Patient]"));
        assertThat(rebooked.status()).isEqualTo(201);
    }

    @Test
    void roleAndAuthenticationRulesAreEnforced() throws Exception {
        String slot = createSlot(Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS));

        Response anonymous = invoke(handler, "POST", "/appointments", null, bookBody(slot), null);
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(anonymous.body().path("error").asText()).isEqualTo("UNAUTHORIZED");

        Response doctorBooking = invoke(handler, "POST", "/appointments", null, bookBody(slot), claims(DOCTOR, "[Doctor]"));
        assertThat(doctorBooking.status()).isEqualTo(403);
        assertThat(doctorBooking.body().path("error").asText()).isEqualTo("FORBIDDEN");
    }

    @Test
    void idempotentRetryReturnsTheSameAppointment() throws Exception {
        String slot = createSlot(Instant.now().plus(5, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS));
        Map<String, String> key = Map.of("Idempotency-Key", "retry-key-" + UUID.randomUUID());

        Response first = invoke(handler, "POST", "/appointments", null, bookBody(slot),
                claims(PATIENT_A, "[Patient]"), key);
        Response retry = invoke(handler, "POST", "/appointments", null, bookBody(slot),
                claims(PATIENT_A, "[Patient]"), key);

        assertThat(first.status()).isEqualTo(201);
        assertThat(retry.status()).isEqualTo(200);
        assertThat(retry.body().path("appointmentId").asText())
                .isEqualTo(first.body().path("appointmentId").asText());
    }

    @Test
    void invalidInputIsRejected() throws Exception {
        String slot = createSlot(Instant.now().plus(4, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS));
        Response bad = invoke(handler, "POST", "/appointments", null, "{\"doctorId\":\"\"}", claims(PATIENT_A, "[Patient]"));
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.body().path("error").asText()).isEqualTo("VALIDATION_FAILED");

        Response missingSlot = invoke(handler, "POST", "/appointments", null,
                bookBody(TimeSupport.toKey(Instant.parse(slot).plus(30, ChronoUnit.MINUTES))),
                claims(PATIENT_A, "[Patient]"));
        assertThat(missingSlot.status()).isEqualTo(404);
        assertThat(missingSlot.body().path("error").asText()).isEqualTo("SLOT_NOT_FOUND");
    }
}
