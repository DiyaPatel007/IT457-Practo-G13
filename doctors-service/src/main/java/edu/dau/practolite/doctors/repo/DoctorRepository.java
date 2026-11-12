package edu.dau.practolite.doctors.repo;

import static edu.dau.practolite.common.model.Attr.bool;
import static edu.dau.practolite.common.model.Attr.n;
import static edu.dau.practolite.common.model.Attr.num;
import static edu.dau.practolite.common.model.Attr.s;
import static edu.dau.practolite.common.model.Attr.str;

import edu.dau.practolite.common.config.AppProperties;
import edu.dau.practolite.common.model.DoctorSearchKeys;
import edu.dau.practolite.common.model.Schema.Doctors;
import edu.dau.practolite.common.web.ApiException;
import java.util.ArrayList;
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

/** Doctors table: AP2 (search through the sparse GSI) and AP3 (get by id). */
@Repository
public class DoctorRepository {

    private final DynamoDbClient dynamo;
    private final String table;

    public DoctorRepository(DynamoDbClient dynamo, AppProperties props) {
        this.dynamo = dynamo;
        this.table = props.tables().doctors();
    }

    public Optional<DoctorRecord> findById(String doctorId) {
        Map<String, AttributeValue> item = dynamo.getItem(b -> b.tableName(table)
                .key(Map.of(Doctors.DOCTOR_ID, s(doctorId)))).item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(map(item));
    }

    /**
     * Writes the profile fields. If the doctor is already verified, the search-index keys are rewritten
     * too, so a doctor who moves city appears under the new city.
     * Optimistic lock on updatedAt, the same as admin verification, so the two never overwrite each other.
     */
    public DoctorRecord updateProfile(DoctorRecord seen, DoctorRecord wanted, String now) {
        List<String> sets = new ArrayList<>();
        List<String> removes = new ArrayList<>();
        Map<String, String> names = new HashMap<>();
        Map<String, AttributeValue> values = new HashMap<>();

        set(sets, names, values, Doctors.NAME, s(wanted.name()));
        set(sets, names, values, Doctors.NAME_LOWER, s(DoctorSearchKeys.normalise(wanted.name())));
        set(sets, names, values, Doctors.SPECIALITY, s(wanted.speciality()));
        set(sets, names, values, Doctors.CITY, s(wanted.city()));
        set(sets, names, values, Doctors.FEE, n(wanted.fee()));
        optional(sets, removes, names, values, Doctors.CLINIC_NAME,
                wanted.clinicName() == null ? null : s(wanted.clinicName()));
        optional(sets, removes, names, values, Doctors.EXPERIENCE_YEARS,
                wanted.experienceYears() == null ? null : n(wanted.experienceYears()));
        optional(sets, removes, names, values, Doctors.BIO, wanted.bio() == null ? null : s(wanted.bio()));
        set(sets, names, values, Doctors.UPDATED_AT, s(now));
        if (seen.verified()) {
            set(sets, names, values, DoctorSearchKeys.SEARCH_CITY, s(DoctorSearchKeys.searchCity(wanted.city())));
            set(sets, names, values, DoctorSearchKeys.SPEC_KEY,
                    s(DoctorSearchKeys.specKey(wanted.speciality(), wanted.name())));
        }
        names.put("#seenUpdatedAt", Doctors.UPDATED_AT);
        names.put("#verified", Doctors.VERIFIED);
        values.put(":seenUpdatedAt", s(seen.updatedAt()));
        values.put(":seenVerified", bool(seen.verified()));

        String expression = "SET " + String.join(", ", sets)
                + (removes.isEmpty() ? "" : " REMOVE " + String.join(", ", removes));
        try {
            Map<String, AttributeValue> updated = dynamo.updateItem(b -> b.tableName(table)
                    .key(Map.of(Doctors.DOCTOR_ID, s(seen.doctorId())))
                    .updateExpression(expression)
                    .conditionExpression("#seenUpdatedAt = :seenUpdatedAt AND #verified = :seenVerified")
                    .expressionAttributeNames(names)
                    .expressionAttributeValues(values)
                    .returnValues(ReturnValue.ALL_NEW)).attributes();
            return map(updated);
        } catch (ConditionalCheckFailedException e) {
            throw ApiException.conflict("CONCURRENT_UPDATE", "The profile changed meanwhile, retry");
        }
    }

    /**
     * Search verified doctors in a city, optionally by speciality and a name fragment.
     * Only verified doctors have searchCity, so the index itself excludes everyone else.
     * The name filter runs inside DynamoDB after the key condition, so a page can contain fewer
     * than {@code limit} items while nextToken is still present (a documented DynamoDB behaviour).
     */
    public Page search(String city, String speciality, String nameFragment, int limit,
                       Map<String, AttributeValue> startKey) {
        Map<String, String> names = new HashMap<>();
        Map<String, AttributeValue> values = new HashMap<>();
        names.put("#city", DoctorSearchKeys.SEARCH_CITY);
        values.put(":city", s(DoctorSearchKeys.searchCity(city)));
        String keyCondition = "#city = :city";
        if (speciality != null && !speciality.isBlank()) {
            names.put("#spec", DoctorSearchKeys.SPEC_KEY);
            values.put(":spec", s(DoctorSearchKeys.specPrefix(speciality)));
            keyCondition += " AND begins_with(#spec, :spec)";
        }
        QueryRequest.Builder query = QueryRequest.builder()
                .tableName(table)
                .indexName(DoctorSearchKeys.INDEX_NAME)
                .keyConditionExpression(keyCondition)
                .limit(limit)
                .exclusiveStartKey(startKey);
        if (nameFragment != null && !nameFragment.isBlank()) {
            names.put("#nameLower", Doctors.NAME_LOWER);
            values.put(":name", s(DoctorSearchKeys.normalise(nameFragment)));
            query.filterExpression("contains(#nameLower, :name)");
        }
        QueryResponse res = dynamo.query(query.expressionAttributeNames(names).expressionAttributeValues(values).build());
        List<DoctorRecord> items = res.items().stream().map(DoctorRepository::map).toList();
        return new Page(items, res.hasLastEvaluatedKey() ? res.lastEvaluatedKey() : null);
    }

    public record Page(List<DoctorRecord> items, Map<String, AttributeValue> lastEvaluatedKey) {
    }

    private static void set(List<String> sets, Map<String, String> names, Map<String, AttributeValue> values,
                            String attribute, AttributeValue value) {
        names.put("#" + attribute, attribute);
        values.put(":" + attribute, value);
        sets.add("#" + attribute + " = :" + attribute);
    }

    private static void optional(List<String> sets, List<String> removes, Map<String, String> names,
                                 Map<String, AttributeValue> values, String attribute, AttributeValue value) {
        if (value == null) {
            names.put("#" + attribute, attribute);
            removes.add("#" + attribute);
        } else {
            set(sets, names, values, attribute, value);
        }
    }

    static DoctorRecord map(Map<String, AttributeValue> item) {
        Long fee = num(item, Doctors.FEE);
        Long experience = num(item, Doctors.EXPERIENCE_YEARS);
        return new DoctorRecord(
                str(item, Doctors.DOCTOR_ID),
                str(item, Doctors.NAME),
                str(item, Doctors.SPECIALITY),
                str(item, Doctors.CITY),
                fee == null ? null : fee.intValue(),
                str(item, Doctors.CLINIC_NAME),
                experience == null ? null : experience.intValue(),
                str(item, Doctors.BIO),
                bool(item, Doctors.VERIFIED),
                str(item, Doctors.UPDATED_AT));
    }
}
