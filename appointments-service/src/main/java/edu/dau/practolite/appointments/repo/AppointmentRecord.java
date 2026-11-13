package edu.dau.practolite.appointments.repo;

import edu.dau.practolite.common.model.AppointmentStatus;

/** One item of the Appointments table. Doctor and patient names are copied in, so listings need no extra reads. */
public record AppointmentRecord(
        String appointmentId,
        String patientId,
        String patientName,
        String doctorId,
        String doctorName,
        String slotStart,
        AppointmentStatus status,
        String reason,
        String cancelledBy,
        String createdAt,
        String updatedAt) {
}
