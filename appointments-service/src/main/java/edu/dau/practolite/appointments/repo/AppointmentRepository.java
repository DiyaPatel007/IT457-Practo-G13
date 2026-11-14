package edu.dau.practolite.appointments.repo;

import static edu.dau.practolite.common.model.Attr.s;
import static edu.dau.practolite.common.model.Attr.str;

import edu.dau.practolite.common.config.AppProperties;
import edu.dau.practolite.common.model.AppointmentStatus;
import edu.dau.practolite.common.model.Schema.Appointments;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;

/** Appointments table: get by id (AP8), list by patient (AP6) or doctor (AP7), status updates. */
@Repository
public class AppointmentRepository {

    private final DynamoDbClient dynamo;
    private final String table;

    public AppointmentRepository(DynamoDbClient dynamo, AppProperties props) {
        this.dynamo = dynamo;
        this.table = props.tables().appointments();
    }

    public String table() {
        return table;
    }

    /** Strongly consistent read: used before cancel or status changes, so we never act on stale data. */
    public Optional<AppointmentRecord> findById(String appointmentId) {
        Map<String, AttributeValue> item = dynamo.getItem(b -> b.tableName(table).consistentRead(true)
                .key(Map.of(Appointments.APPOINTMENT_ID, s(appointmentId)))).item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(map(item));
    }

    /**
     * Lists one patient's or one doctor's appointments in a time range, using patient-index or doctor-index.
     * GSI reads are eventually consistent: a booking made a moment ago may take a short time to appear.
     */
    public Page listByIndex(String indexName, String partitionAttribute, String partitionValue, String fromKey,
                            String toKey, AppointmentStatus status, int limit, Map<String, AttributeValue> startKey) {
        Map<String, String> names = new HashMap<>();
        names.put("#pk", partitionAttribute);
        names.put("#slotStart", Appointments.SLOT_START);
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":pk", s(partitionValue));
        values.put(":from", s(fromKey));
        values.put(":to", s(toKey));
        QueryRequest.Builder query = QueryRequest.builder()
                .tableName(table)
                .indexName(indexName)
                .keyConditionExpression("#pk = :pk AND #slotStart BETWEEN :from AND :to")
                .limit(limit)
                .exclusiveStartKey(startKey);
        if (status != null) {
            names.put("#status", Appointments.STATUS);
            values.put(":status", s(status.name()));
            query.filterExpression("#status = :status");
        }
        QueryResponse res = dynamo.query(query.expressionAttributeNames(names).expressionAttributeValues(values).build());
        return new Page(res.items().stream().map(AppointmentRepository::map).toList(),
                res.hasLastEvaluatedKey() ? res.lastEvaluatedKey() : null);
    }

    /** Doctor marks COMPLETED or NO_SHOW. Condition: still BOOKED and still this doctor's appointment. */
    public AppointmentRecord updateStatusIfBooked(String appointmentId, String doctorId, AppointmentStatus newStatus,
                                                  String now) {
        try {
            Map<String, AttributeValue> updated = dynamo.updateItem(b -> b.tableName(table)
                    .key(Map.of(Appointments.APPOINTMENT_ID, s(appointmentId)))
                    .updateExpression("SET #status = :new, #updatedAt = :now")
                    .conditionExpression("#status = :booked AND #doctorId = :doctorId")
                    .expressionAttributeNames(Map.of("#status", Appointments.STATUS,
                            "#updatedAt", Appointments.UPDATED_AT, "#doctorId", Appointments.DOCTOR_ID))
                    .expressionAttributeValues(Map.of(":new", s(newStatus.name()), ":now", s(now),
                            ":booked", s(AppointmentStatus.BOOKED.name()), ":doctorId", s(doctorId)))
                    .returnValues(ReturnValue.ALL_NEW)).attributes();
            return map(updated);
        } catch (ConditionalCheckFailedException e) {
            throw edu.dau.practolite.common.web.ApiException.conflict("INVALID_TRANSITION",
                    "The appointment is no longer BOOKED");
        }
    }

    /** Unconditional put. Used ONLY by the naive booking strategy (Experiment 4 contrast). */
    public void putUnconditionally(AppointmentRecord record) {
        dynamo.putItem(b -> b.tableName(table).item(toItem(record)));
    }

    public record Page(List<AppointmentRecord> items, Map<String, AttributeValue> lastEvaluatedKey) {
    }

    public static Map<String, AttributeValue> toItem(AppointmentRecord r) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(Appointments.APPOINTMENT_ID, s(r.appointmentId()));
        item.put(Appointments.PATIENT_ID, s(r.patientId()));
        item.put(Appointments.DOCTOR_ID, s(r.doctorId()));
        item.put(Appointments.SLOT_START, s(r.slotStart()));
        item.put(Appointments.STATUS, s(r.status().name()));
        item.put(Appointments.CREATED_AT, s(r.createdAt()));
        item.put(Appointments.UPDATED_AT, s(r.updatedAt()));
        putIfPresent(item, Appointments.PATIENT_NAME, r.patientName());
        putIfPresent(item, Appointments.DOCTOR_NAME, r.doctorName());
        putIfPresent(item, Appointments.REASON, r.reason());
        putIfPresent(item, Appointments.CANCELLED_BY, r.cancelledBy());
        return item;
    }

    public static AppointmentRecord map(Map<String, AttributeValue> item) {
        return new AppointmentRecord(
                str(item, Appointments.APPOINTMENT_ID),
                str(item, Appointments.PATIENT_ID),
                str(item, Appointments.PATIENT_NAME),
                str(item, Appointments.DOCTOR_ID),
                str(item, Appointments.DOCTOR_NAME),
                str(item, Appointments.SLOT_START),
                AppointmentStatus.valueOf(str(item, Appointments.STATUS)),
                str(item, Appointments.REASON),
                str(item, Appointments.CANCELLED_BY),
                str(item, Appointments.CREATED_AT),
                str(item, Appointments.UPDATED_AT));
    }

    private static void putIfPresent(Map<String, AttributeValue> item, String name, String value) {
        if (value != null && !value.isBlank()) {
            item.put(name, s(value));
        }
    }
}
