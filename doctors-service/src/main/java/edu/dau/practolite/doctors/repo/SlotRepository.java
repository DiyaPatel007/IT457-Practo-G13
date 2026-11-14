package edu.dau.practolite.doctors.repo;

import static edu.dau.practolite.common.model.Attr.n;
import static edu.dau.practolite.common.model.Attr.s;
import static edu.dau.practolite.common.model.Attr.str;

import edu.dau.practolite.common.config.AppProperties;
import edu.dau.practolite.common.model.Schema.Slots;
import edu.dau.practolite.common.model.SlotStatus;
import edu.dau.practolite.common.web.ApiException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ReturnValuesOnConditionCheckFailure;

/** Slots table (PK doctorId, SK slotStart): create, list a time range (AP4), delete if unbooked. */
@Repository
public class SlotRepository {

    private final DynamoDbClient dynamo;
    private final String table;

    public SlotRepository(DynamoDbClient dynamo, AppProperties props) {
        this.dynamo = dynamo;
        this.table = props.tables().slots();
    }

    /**
     * Creates the slot only if no slot exists at that start time. Because all slots are 30 minutes on a
     * fixed grid, "same start time" is the only possible overlap, so this condition alone prevents overlaps,
     * with no read-then-write race.
     *
     * @return false if the slot already existed
     */
    public boolean createIfAbsent(String doctorId, String slotStartKey, String now) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(Slots.DOCTOR_ID, s(doctorId));
        item.put(Slots.SLOT_START, s(slotStartKey));
        item.put(Slots.STATUS, s(SlotStatus.AVAILABLE.name()));
        item.put(Slots.DURATION_MIN, n(Slots.DURATION_MINUTES));
        item.put(Slots.CREATED_AT, s(now));
        try {
            dynamo.putItem(b -> b.tableName(table).item(item)
                    .conditionExpression("attribute_not_exists(" + Slots.SLOT_START + ")"));
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    /** All slots of a doctor with fromKey <= slotStart <= toKey, optionally only AVAILABLE ones. */
    public List<SlotRecord> findRange(String doctorId, String fromKey, String toKey, boolean availableOnly) {
        Map<String, String> names = new HashMap<>();
        names.put("#doctorId", Slots.DOCTOR_ID);
        names.put("#slotStart", Slots.SLOT_START);
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":doctorId", s(doctorId));
        values.put(":from", s(fromKey));
        values.put(":to", s(toKey));
        if (availableOnly) {
            names.put("#status", Slots.STATUS);
            values.put(":available", s(SlotStatus.AVAILABLE.name()));
        }
        List<SlotRecord> result = new ArrayList<>();
        Map<String, AttributeValue> startKey = null;
        do {
            Map<String, AttributeValue> exclusiveStart = startKey;
            QueryResponse res = dynamo.query(b -> {
                b.tableName(table)
                        .keyConditionExpression("#doctorId = :doctorId AND #slotStart BETWEEN :from AND :to")
                        .expressionAttributeNames(names)
                        .expressionAttributeValues(values)
                        .exclusiveStartKey(exclusiveStart);
                if (availableOnly) {
                    b.filterExpression("#status = :available");
                }
            });
            res.items().forEach(i -> result.add(new SlotRecord(
                    str(i, Slots.DOCTOR_ID), str(i, Slots.SLOT_START), str(i, Slots.STATUS))));
            startKey = res.hasLastEvaluatedKey() ? res.lastEvaluatedKey() : null;
        } while (startKey != null);
        return result;
    }

    /**
     * Deletes the slot only while it is AVAILABLE. A booked slot cannot disappear underneath an appointment.
     * ReturnValuesOnConditionCheckFailure tells us why the condition failed without a second read.
     */
    public void deleteIfAvailable(String doctorId, String slotStartKey) {
        try {
            dynamo.deleteItem(b -> b.tableName(table)
                    .key(Map.of(Slots.DOCTOR_ID, s(doctorId), Slots.SLOT_START, s(slotStartKey)))
                    .conditionExpression("attribute_exists(#slotStart) AND #status = :available")
                    .expressionAttributeNames(Map.of("#slotStart", Slots.SLOT_START, "#status", Slots.STATUS))
                    .expressionAttributeValues(Map.of(":available", s(SlotStatus.AVAILABLE.name())))
                    .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD));
        } catch (ConditionalCheckFailedException e) {
            if (e.hasItem() && !e.item().isEmpty()) {
                throw ApiException.conflict("SLOT_BOOKED", "A booked slot cannot be deleted; cancel the appointment first");
            }
            throw ApiException.notFound("SLOT_NOT_FOUND", "No slot at this time");
        }
    }

    public record SlotRecord(String doctorId, String slotStart, String status) {
    }
}
