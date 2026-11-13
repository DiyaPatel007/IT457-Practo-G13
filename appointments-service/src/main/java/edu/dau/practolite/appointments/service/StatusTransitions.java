package edu.dau.practolite.appointments.service;

import edu.dau.practolite.appointments.repo.AppointmentRecord;
import edu.dau.practolite.common.model.AppointmentStatus;
import edu.dau.practolite.common.web.ApiException;
import java.time.Instant;

/**
 * Which status changes are allowed, and when.
 * <pre>
 *   BOOKED -> CANCELLED              patient or doctor, before the slot starts
 *   BOOKED -> COMPLETED | NO_SHOW    doctor only, after the slot starts
 * </pre>
 * With strict time rules off (STRICT_TIME_RULES=false, for live demos) the time checks are skipped.
 */
public final class StatusTransitions {

    private StatusTransitions() {
    }

    public static void checkCancel(AppointmentRecord a, Instant now, boolean strict) {
        if (a.status() != AppointmentStatus.BOOKED) {
            throw ApiException.conflict("NOT_CANCELLABLE", "Only BOOKED appointments can be cancelled");
        }
        if (strict && !Instant.parse(a.slotStart()).isAfter(now)) {
            throw ApiException.conflict("TOO_LATE_TO_CANCEL", "The appointment has already started");
        }
    }

    public static void checkDoctorUpdate(AppointmentRecord a, AppointmentStatus target, Instant now, boolean strict) {
        if (target != AppointmentStatus.COMPLETED && target != AppointmentStatus.NO_SHOW) {
            throw ApiException.badRequest("INVALID_STATUS", "status must be COMPLETED or NO_SHOW");
        }
        if (a.status() != AppointmentStatus.BOOKED) {
            throw ApiException.conflict("INVALID_TRANSITION", "Only BOOKED appointments can be updated");
        }
        if (strict && Instant.parse(a.slotStart()).isAfter(now)) {
            throw ApiException.conflict("TOO_EARLY", "The appointment has not started yet");
        }
    }
}
