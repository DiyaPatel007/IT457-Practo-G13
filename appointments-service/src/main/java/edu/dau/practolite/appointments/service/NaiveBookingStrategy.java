package edu.dau.practolite.appointments.service;

import edu.dau.practolite.appointments.config.BookingProperties;
import edu.dau.practolite.appointments.repo.AppointmentRecord;
import edu.dau.practolite.appointments.repo.AppointmentRepository;
import edu.dau.practolite.appointments.repo.SlotStateRepository;
import edu.dau.practolite.common.model.SlotStatus;
import edu.dau.practolite.common.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * DELIBERATELY UNSAFE. For the Experiment 4 contrast only (BOOKING_MODE=naive on a test stack).
 *
 * <p>Read the slot, check it is AVAILABLE, then write the appointment and mark the slot BOOKED as
 * separate, unconditional writes. Two requests can both read AVAILABLE before either writes, and then both
 * create an appointment: a double booking. The transactional strategy closes this gap by making the
 * check and the writes one atomic operation.
 */
@Component
@ConditionalOnProperty(name = "booking.mode", havingValue = "naive")
public class NaiveBookingStrategy implements BookingStrategy {

    private static final Logger LOG = LoggerFactory.getLogger(NaiveBookingStrategy.class);

    private final SlotStateRepository slots;
    private final AppointmentRepository appointments;
    private final long delayMs;

    public NaiveBookingStrategy(SlotStateRepository slots, AppointmentRepository appointments, BookingProperties props) {
        this.slots = slots;
        this.appointments = appointments;
        this.delayMs = props.naiveDelayMs();
        LOG.warn("NAIVE booking mode is active (delay {} ms). Use only for Experiment 4.", delayMs);
    }

    @Override
    public BookingResult book(AppointmentRecord appointment) {
        String status = slots.readStatus(appointment.doctorId(), appointment.slotStart())
                .orElseThrow(() -> ApiException.notFound("SLOT_NOT_FOUND", "The doctor has no slot at this time"));
        if (!SlotStatus.AVAILABLE.name().equals(status)) {
            throw ApiException.conflict("SLOT_TAKEN", "This slot has already been booked");
        }
        pause();
        appointments.putUnconditionally(appointment);
        slots.markBookedUnconditionally(appointment.doctorId(), appointment.slotStart(), appointment.appointmentId());
        return new BookingResult(appointment, false);
    }

    private void pause() {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public String name() {
        return "naive";
    }
}
