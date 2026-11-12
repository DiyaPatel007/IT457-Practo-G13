package edu.dau.practolite.common.model;

/**
 * Appointment life cycle:
 * <pre>
 *   BOOKED --cancel (before slotStart, patient or doctor)--> CANCELLED
 *   BOOKED --status (after slotStart, doctor)-------------> COMPLETED | NO_SHOW
 * </pre>
 * CANCELLED, COMPLETED and NO_SHOW are final.
 */
public enum AppointmentStatus {
    BOOKED,
    CANCELLED,
    COMPLETED,
    NO_SHOW
}
