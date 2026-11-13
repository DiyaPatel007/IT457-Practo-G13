package edu.dau.practolite.appointments.service;

import edu.dau.practolite.appointments.repo.AppointmentRecord;

/**
 * How a booking is written. Two implementations, chosen by BOOKING_MODE:
 * <ul>
 *   <li>{@link TransactionalBookingStrategy} (default): one atomic conditional transaction. Correct.</li>
 *   <li>{@link NaiveBookingStrategy}: read, check, then write. Unsafe under concurrency. It exists only
 *       so Experiment 4 can show the double booking that the transaction prevents.</li>
 * </ul>
 */
public interface BookingStrategy {

    /** @return the stored appointment and whether this was a replay of an earlier identical request */
    BookingResult book(AppointmentRecord appointment);

    String name();

    record BookingResult(AppointmentRecord appointment, boolean replayed) {
    }
}
