package edu.dau.practolite.users.repo;

import static edu.dau.practolite.common.model.Attr.bool;
import static edu.dau.practolite.common.model.Attr.s;
import static edu.dau.practolite.common.model.Attr.str;

import edu.dau.practolite.common.config.AppProperties;
import edu.dau.practolite.common.model.DoctorSearchKeys;
import edu.dau.practolite.common.model.Schema.Doctors;
import edu.dau.practolite.common.web.ApiException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * The Users service's narrow view of the Doctors table: create the unverified doctor item at
 * registration, and set or clear verification (admin). Profile fields are owned by the Doctors service.
 */
@Repository
public class DoctorAccountRepository {

    private final DynamoDbClient dynamo;
    private final String table;

    public DoctorAccountRepository(DynamoDbClient dynamo, AppProperties props) {
        this.dynamo = dynamo;
        this.table = props.tables().doctors();
    }

    /** New doctors start unverified, so they cannot be found or booked until an admin verifies them. */
    public void createUnverified(String doctorId, String name, String now) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(Doctors.DOCTOR_ID, s(doctorId));
        item.put(Doctors.NAME, s(name));
        item.put(Doctors.NAME_LOWER, s(DoctorSearchKeys.normalise(name)));
        item.put(Doctors.VERIFIED, bool(false));
        item.put(Doctors.CREATED_AT, s(now));
        item.put(Doctors.UPDATED_AT, s(now));
        dynamo.putItem(b -> b.tableName(table).item(item)
                .conditionExpression("attribute_not_exists(" + Doctors.DOCTOR_ID + ")"));
    }

    public Optional<DoctorAccount> findById(String doctorId) {
        Map<String, AttributeValue> item = dynamo.getItem(b -> b.tableName(table).consistentRead(true)
                .key(Map.of(Doctors.DOCTOR_ID, s(doctorId)))).item();
        if (item == null || item.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new DoctorAccount(
                str(item, Doctors.DOCTOR_ID), str(item, Doctors.NAME), str(item, Doctors.CITY),
                str(item, Doctors.SPECIALITY), bool(item, Doctors.VERIFIED), str(item, Doctors.UPDATED_AT)));
    }

    /**
     * Sets verified and writes (or removes) the sparse search-index keys in one update.
     * Optimistic locking on updatedAt: if the doctor changed their profile after we read it,
     * the condition fails and the admin simply retries, so stale city or speciality keys are never written.
     */
    public void setVerification(DoctorAccount seen, boolean verified, String now) {
        Map<String, String> names = new HashMap<>();
        names.put("#verified", Doctors.VERIFIED);
        names.put("#updatedAt", Doctors.UPDATED_AT);
        names.put("#searchCity", DoctorSearchKeys.SEARCH_CITY);
        names.put("#specKey", DoctorSearchKeys.SPEC_KEY);
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":verified", bool(verified));
        values.put(":now", s(now));
        values.put(":seen", s(seen.updatedAt()));

        String update;
        if (verified) {
            update = "SET #verified = :verified, #updatedAt = :now, #searchCity = :city, #specKey = :spec";
            values.put(":city", s(DoctorSearchKeys.searchCity(seen.city())));
            values.put(":spec", s(DoctorSearchKeys.specKey(seen.speciality(), seen.name())));
        } else {
            update = "SET #verified = :verified, #updatedAt = :now REMOVE #searchCity, #specKey";
        }
        try {
            String expression = update;
            dynamo.updateItem(b -> b.tableName(table)
                    .key(Map.of(Doctors.DOCTOR_ID, s(seen.doctorId())))
                    .updateExpression(expression)
                    .conditionExpression("#updatedAt = :seen")
                    .expressionAttributeNames(names)
                    .expressionAttributeValues(values));
        } catch (ConditionalCheckFailedException e) {
            throw ApiException.conflict("CONCURRENT_UPDATE", "The doctor profile changed meanwhile, retry");
        }
    }

    public void delete(String doctorId) {
        dynamo.deleteItem(b -> b.tableName(table).key(Map.of(Doctors.DOCTOR_ID, s(doctorId))));
    }

    public record DoctorAccount(String doctorId, String name, String city, String speciality,
                                boolean verified, String updatedAt) {
    }
}
