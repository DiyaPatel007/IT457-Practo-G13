package edu.dau.practolite.appointments.service;

import static edu.dau.practolite.appointments.repo.BookingTransactions.APPOINTMENT_ITEM;
import static edu.dau.practolite.appointments.repo.BookingTransactions.SLOT_ITEM;

import edu.dau.practolite.common.web.ApiException;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * Turns a cancelled DynamoDB transaction into the right HTTP answer. The distinction matters for
 * Experiment 4, which counts each outcome separately:
 *
 * <table>
 *   <tr><th>DynamoDB reason</th><th>HTTP</th><th>Meaning</th></tr>
 *   <tr><td>ConditionalCheckFailed on the slot, slot exists</td><td>409 SLOT_TAKEN</td><td>correct rejection</td></tr>
 *   <tr><td>ConditionalCheckFailed on the slot, no slot</td><td>404 SLOT_NOT_FOUND</td><td>bad input</td></tr>
 *   <tr><td>TransactionConflict</td><td>409 CONFLICT_RETRY</td><td>another transaction held the item</td></tr>
 *   <tr><td>ThrottlingError / ProvisionedThroughputExceeded</td><td>503 CAPACITY</td><td>capacity, not consistency</td></tr>
 * </table>
 */
public final class TransactionErrorMapper {

    private static final Logger LOG = LoggerFactory.getLogger(TransactionErrorMapper.class);

    static final String CONDITION_FAILED = "ConditionalCheckFailed";
    static final String CONFLICT = "TransactionConflict";
    static final Set<String> THROTTLING = Set.of("ThrottlingError", "ProvisionedThroughputExceeded",
            "RequestLimitExceeded");

    private TransactionErrorMapper() {
    }

    public static String code(TransactionCanceledException e, int index) {
        List<CancellationReason> reasons = e.hasCancellationReasons() ? e.cancellationReasons() : List.of();
        return index < reasons.size() && reasons.get(index).code() != null ? reasons.get(index).code() : "None";
    }

    /** The slot as it was when the booking transaction failed (empty if it does not exist). */
    public static CancellationReason slotReason(TransactionCanceledException e) {
        return e.hasCancellationReasons() && e.cancellationReasons().size() > SLOT_ITEM
                ? e.cancellationReasons().get(SLOT_ITEM) : null;
    }

    public static ApiException forBooking(TransactionCanceledException e) {
        String slot = code(e, SLOT_ITEM);
        String appointment = code(e, APPOINTMENT_ITEM);
        if (CONDITION_FAILED.equals(slot)) {
            CancellationReason r = slotReason(e);
            boolean slotExists = r != null && r.hasItem() && !r.item().isEmpty();
            return slotExists
                    ? ApiException.conflict("SLOT_TAKEN", "This slot has already been booked")
                    : ApiException.notFound("SLOT_NOT_FOUND", "The doctor has no slot at this time");
        }
        ApiException common = throttlingOrConflict(slot, appointment);
        if (common != null) {
            return common;
        }
        if (CONDITION_FAILED.equals(appointment)) {
            return ApiException.conflict("DUPLICATE_APPOINTMENT", "This booking request was already processed");
        }
        LOG.error("Unexpected booking cancellation reasons: slot={} appointment={}", slot, appointment);
        throw new IllegalStateException("Unexpected transaction cancellation", e);
    }

    public static ApiException forCancel(TransactionCanceledException e) {
        String slot = code(e, SLOT_ITEM);
        String appointment = code(e, APPOINTMENT_ITEM);
        if (CONDITION_FAILED.equals(appointment)) {
            return ApiException.conflict("NOT_CANCELLABLE", "The appointment is no longer BOOKED");
        }
        if (CONDITION_FAILED.equals(slot)) {
            // Would mean the invariant is broken (slot does not point at this appointment). Never expected.
            LOG.error("Invariant violation: slot does not reference the appointment being cancelled");
            return ApiException.conflict("SLOT_STATE_MISMATCH", "Slot state does not match the appointment");
        }
        ApiException common = throttlingOrConflict(slot, appointment);
        if (common != null) {
            return common;
        }
        LOG.error("Unexpected cancel cancellation reasons: slot={} appointment={}", slot, appointment);
        throw new IllegalStateException("Unexpected transaction cancellation", e);
    }

    private static ApiException throttlingOrConflict(String slot, String appointment) {
        if (THROTTLING.contains(slot) || THROTTLING.contains(appointment)) {
            return ApiException.unavailable("CAPACITY", "The database is temporarily over capacity, retry later");
        }
        if (CONFLICT.equals(slot) || CONFLICT.equals(appointment)) {
            return ApiException.conflict("CONFLICT_RETRY", "Another request is changing this slot, try again");
        }
        return null;
    }
}
