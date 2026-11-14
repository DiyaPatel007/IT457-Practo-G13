package edu.dau.practolite.appointments.repo;

import static edu.dau.practolite.common.model.Attr.s;
import static edu.dau.practolite.common.model.Attr.str;

import edu.dau.practolite.common.config.AppProperties;
import edu.dau.practolite.common.model.Schema.Slots;
import edu.dau.practolite.common.model.SlotStatus;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Plain (non-transactional) slot reads and writes. Used ONLY by the naive booking strategy, which exists
 * to show in Experiment 4 why read-then-write booking is unsafe.
 */
@Repository
public class SlotStateRepository {

    private final DynamoDbClient dynamo;
    private final String table;

    public SlotStateRepository(DynamoDbClient dynamo, AppProperties props) {
        this.dynamo = dynamo;
        this.table = props.tables().slots();
    }

    public Optional<String> readStatus(String doctorId, String slotStart) {
        Map<String, AttributeValue> item = dynamo.getItem(b -> b.tableName(table).consistentRead(true)
                .key(Map.of(Slots.DOCTOR_ID, s(doctorId), Slots.SLOT_START, s(slotStart)))).item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.ofNullable(str(item, Slots.STATUS));
    }

    /** Unconditional write: whoever writes last wins. This is exactly the bug the transaction prevents. */
    public void markBookedUnconditionally(String doctorId, String slotStart, String appointmentId) {
        dynamo.updateItem(b -> b.tableName(table)
                .key(Map.of(Slots.DOCTOR_ID, s(doctorId), Slots.SLOT_START, s(slotStart)))
                .updateExpression("SET #status = :booked, #aid = :aid")
                .expressionAttributeNames(Map.of("#status", Slots.STATUS, "#aid", Slots.APPOINTMENT_ID))
                .expressionAttributeValues(Map.of(":booked", s(SlotStatus.BOOKED.name()), ":aid", s(appointmentId))));
    }
}
