package edu.dau.practolite.appointments.service;

import static edu.dau.practolite.common.model.Attr.str;

import edu.dau.practolite.appointments.repo.AppointmentRecord;
import edu.dau.practolite.appointments.repo.AppointmentRepository;
import edu.dau.practolite.appointments.repo.BookingTransactions;
import edu.dau.practolite.common.model.Schema.Slots;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/** The correct booking path: a single TransactWriteItems call (see {@link BookingTransactions}). */
@Component
@ConditionalOnProperty(name = "booking.mode", havingValue = "transactional", matchIfMissing = true)
public class TransactionalBookingStrategy implements BookingStrategy {

    private final BookingTransactions transactions;
    private final AppointmentRepository appointments;

    public TransactionalBookingStrategy(BookingTransactions transactions, AppointmentRepository appointments) {
        this.transactions = transactions;
        this.appointments = appointments;
    }

    @Override
    public BookingResult book(AppointmentRecord appointment) {
        try {
            transactions.book(appointment);
            return new BookingResult(appointment, false);
        } catch (TransactionCanceledException e) {
            // Idempotent retry: the slot is already booked by THIS appointment id (same patient, same key).
            Optional<AppointmentRecord> replay = replayOf(appointment, e);
            if (replay.isPresent()) {
                return new BookingResult(replay.get(), true);
            }
            throw TransactionErrorMapper.forBooking(e);
        }
    }

    private Optional<AppointmentRecord> replayOf(AppointmentRecord wanted, TransactionCanceledException e) {
        CancellationReason slot = TransactionErrorMapper.slotReason(e);
        if (slot == null || !slot.hasItem()) {
            return Optional.empty();
        }
        String bookedBy = str(slot.item(), Slots.APPOINTMENT_ID);
        if (!wanted.appointmentId().equals(bookedBy)) {
            return Optional.empty();
        }
        return appointments.findById(wanted.appointmentId());
    }

    @Override
    public String name() {
        return "transactional";
    }
}
