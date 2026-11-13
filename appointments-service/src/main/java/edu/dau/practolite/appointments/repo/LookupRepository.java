package edu.dau.practolite.appointments.repo;

import static edu.dau.practolite.common.model.Attr.bool;
import static edu.dau.practolite.common.model.Attr.s;
import static edu.dau.practolite.common.model.Attr.str;

import edu.dau.practolite.common.config.AppProperties;
import edu.dau.practolite.common.model.Schema.Doctors;
import edu.dau.practolite.common.model.Schema.Users;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/** Read-only lookups in tables owned by other services (IAM allows only GetItem on them). */
@Repository
public class LookupRepository {

    private final DynamoDbClient dynamo;
    private final String doctorsTable;
    private final String usersTable;

    public LookupRepository(DynamoDbClient dynamo, AppProperties props) {
        this.dynamo = dynamo;
        this.doctorsTable = props.tables().doctors();
        this.usersTable = props.tables().users();
    }

    public Optional<DoctorInfo> findDoctor(String doctorId) {
        Map<String, AttributeValue> item = dynamo.getItem(b -> b.tableName(doctorsTable)
                .key(Map.of(Doctors.DOCTOR_ID, s(doctorId)))
                .projectionExpression("#id, #name, #verified")
                .expressionAttributeNames(Map.of("#id", Doctors.DOCTOR_ID, "#name", Doctors.NAME,
                        "#verified", Doctors.VERIFIED))).item();
        if (item == null || item.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new DoctorInfo(str(item, Doctors.DOCTOR_ID), str(item, Doctors.NAME),
                bool(item, Doctors.VERIFIED)));
    }

    public Optional<String> findUserName(String userId) {
        Map<String, AttributeValue> item = dynamo.getItem(b -> b.tableName(usersTable)
                .key(Map.of(Users.USER_ID, s(userId)))
                .projectionExpression("#name")
                .expressionAttributeNames(Map.of("#name", Users.NAME))).item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.ofNullable(str(item, Users.NAME));
    }

    public record DoctorInfo(String doctorId, String name, boolean verified) {
    }
}
