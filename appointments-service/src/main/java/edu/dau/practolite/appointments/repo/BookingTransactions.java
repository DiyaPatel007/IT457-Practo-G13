package edu.dau.practolite.appointments.repo;

import static edu.dau.practolite.common.model.Attr.s;

import edu.dau.practolite.common.config.AppProperties;
import edu.dau.practolite.common.model.AppointmentStatus;
import edu.dau.practolite.common.model.Schema.Appointments;
import edu.dau.practolite.common.model.Schema.Slots;
import edu.dau.practolite.common.model.SlotStatus;
import java.util.Map;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.ReturnValuesOnConditionCheckFailure;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.Update;

/**
 * The two multi-item writes that must be all-or-nothing. Both use DynamoDB TransactWriteItems:
 * DynamoDB checks every condition and applies every write together, or applies none and throws
 * TransactionCanceledException with one cancellation reason per item (same order as the items).
 *
 * <p><b>Invariant:</b> for every slot, at most one non-cancelled appointment refers to it, and the slot is
 * BOOKED if and only if that appointment exists.
 */
@Repository
public class BookingTransactions {

    /** Position of each item in the transaction, used to read cancellation reasons. */
    public static final int SLOT_ITEM = 0;
    public static final int APPOINTMENT_ITEM = 1;

    private final DynamoDbClient dynamo;
    private final String slotsTable;
    private final String appointmentsTable;

    public BookingTransactions(DynamoDbClient dynamo, AppProperties props) {
        this.dynamo = dynamo;
        this.slotsTable = props.tables().slots();
        this.appointmentsTable = props.tables().appointments();
    }

    /**
     * Book = [ Update slot SET BOOKED IF status = AVAILABLE ,  Put appointment IF it does not exist ].
     * If 100 patients race for one slot, exactly one transaction can see AVAILABLE; all others fail
     * with ConditionalCheckFailed (or TransactionConflict while the winner is in progress).
     */
    public TransactWriteItemsRequest bookRequest(AppointmentRecord appointment) {
        Update lockSlot = Update.builder()
                .tableName(slotsTable)
                .key(Map.of(Slots.DOCTOR_ID, s(appointment.doctorId()), Slots.SLOT_START, s(appointment.slotStart())))
                .updateExpression("SET #status = :booked, #appointmentId = :aid")
                .conditionExpression("attribute_exists(#slotStart) AND #status = :available")
                .expressionAttributeNames(Map.of("#status", Slots.STATUS, "#appointmentId", Slots.APPOINTMENT_ID,
                        "#slotStart", Slots.SLOT_START))
                .expressionAttributeValues(Map.of(
                        ":booked", s(SlotStatus.BOOKED.name()),
                        ":available", s(SlotStatus.AVAILABLE.name()),
                        ":aid", s(appointment.appointmentId())))
                // On failure, return the slot as it is now: tells "slot missing" (404) from "slot taken" (409).
                .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)
                .build();
        Put createAppointment = Put.builder()
                .tableName(appointmentsTable)
                .item(AppointmentRepository.toItem(appointment))
                .conditionExpression("attribute_not_exists(#id)")
                .expressionAttributeNames(Map.of("#id", Appointments.APPOINTMENT_ID))
                .build();
        return TransactWriteItemsRequest.builder().transactItems(
                TransactWriteItem.builder().update(lockSlot).build(),
                TransactWriteItem.builder().put(createAppointment).build()).build();
    }

    public void book(AppointmentRecord appointment) {
        dynamo.transactWriteItems(bookRequest(appointment));
    }

    /**
     * Cancel = [ Update slot SET AVAILABLE, REMOVE appointmentId IF appointmentId = this one ,
     *            Update appointment SET CANCELLED IF status = BOOKED ].
     * The slot becomes bookable again in the same atomic step.
     */
    public TransactWriteItemsRequest cancelRequest(AppointmentRecord appointment, String cancelledBy, String now) {
        Update releaseSlot = Update.builder()
                .tableName(slotsTable)
                .key(Map.of(Slots.DOCTOR_ID, s(appointment.doctorId()), Slots.SLOT_START, s(appointment.slotStart())))
                .updateExpression("SET #status = :available REMOVE #appointmentId")
                .conditionExpression("#appointmentId = :aid")
                .expressionAttributeNames(Map.of("#status", Slots.STATUS, "#appointmentId", Slots.APPOINTMENT_ID))
                .expressionAttributeValues(Map.of(":available", s(SlotStatus.AVAILABLE.name()),
                        ":aid", s(appointment.appointmentId())))
                .build();
        Update cancelAppointment = Update.builder()
                .tableName(appointmentsTable)
                .key(Map.of(Appointments.APPOINTMENT_ID, s(appointment.appointmentId())))
                .updateExpression("SET #status = :cancelled, #updatedAt = :now, #cancelledBy = :by")
                .conditionExpression("#status = :booked")
                .expressionAttributeNames(Map.of("#status", Appointments.STATUS, "#updatedAt", Appointments.UPDATED_AT,
                        "#cancelledBy", Appointments.CANCELLED_BY))
                .expressionAttributeValues(Map.of(":cancelled", s(AppointmentStatus.CANCELLED.name()),
                        ":booked", s(AppointmentStatus.BOOKED.name()), ":now", s(now), ":by", s(cancelledBy)))
                .build();
        return TransactWriteItemsRequest.builder().transactItems(
                TransactWriteItem.builder().update(releaseSlot).build(),
                TransactWriteItem.builder().update(cancelAppointment).build()).build();
    }

    public void cancel(AppointmentRecord appointment, String cancelledBy, String now) {
        dynamo.transactWriteItems(cancelRequest(appointment, cancelledBy, now));
    }
}
