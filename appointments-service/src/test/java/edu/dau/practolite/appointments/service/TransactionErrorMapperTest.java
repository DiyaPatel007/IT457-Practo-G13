package edu.dau.practolite.appointments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.dau.practolite.common.web.ApiException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

class TransactionErrorMapperTest {

    private static TransactionCanceledException cancelled(CancellationReason slot, CancellationReason appointment) {
        return TransactionCanceledException.builder().message("cancelled").cancellationReasons(slot, appointment).build();
    }

    private static CancellationReason reason(String code) {
        return CancellationReason.builder().code(code).build();
    }

    private static CancellationReason failedWithItem() {
        return CancellationReason.builder().code("ConditionalCheckFailed")
                .item(Map.of("status", AttributeValue.fromS("BOOKED"))).build();
    }

    @Test
    void slotAlreadyBookedIsSlotTaken409() {
        ApiException e = TransactionErrorMapper.forBooking(cancelled(failedWithItem(), reason("None")));
        assertThat(e.status().value()).isEqualTo(409);
        assertThat(e.code()).isEqualTo("SLOT_TAKEN");
    }

    @Test
    void missingSlotIsNotFound404() {
        ApiException e = TransactionErrorMapper.forBooking(cancelled(reason("ConditionalCheckFailed"), reason("None")));
        assertThat(e.status().value()).isEqualTo(404);
        assertThat(e.code()).isEqualTo("SLOT_NOT_FOUND");
    }

    @Test
    void transactionConflictIsRetryable409() {
        ApiException e = TransactionErrorMapper.forBooking(cancelled(reason("TransactionConflict"), reason("None")));
        assertThat(e.code()).isEqualTo("CONFLICT_RETRY");
        assertThat(e.status().value()).isEqualTo(409);
    }

    @Test
    void throttlingIsCapacity503NotAConflict() {
        ApiException e = TransactionErrorMapper.forBooking(
                cancelled(reason("None"), reason("ProvisionedThroughputExceeded")));
        assertThat(e.status().value()).isEqualTo(503);
        assertThat(e.code()).isEqualTo("CAPACITY");
    }

    @Test
    void cancelOfNoLongerBookedAppointmentIsNotCancellable() {
        ApiException e = TransactionErrorMapper.forCancel(cancelled(reason("None"), reason("ConditionalCheckFailed")));
        assertThat(e.code()).isEqualTo("NOT_CANCELLABLE");
    }

    @Test
    void unknownReasonsAreNotHidden() {
        assertThatThrownBy(() -> TransactionErrorMapper.forBooking(cancelled(reason("ValidationError"), reason("None"))))
                .isInstanceOf(IllegalStateException.class);
    }
}
