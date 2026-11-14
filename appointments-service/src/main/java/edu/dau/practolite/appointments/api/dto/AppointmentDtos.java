package edu.dau.practolite.appointments.api.dto;

import edu.dau.practolite.common.model.AppointmentStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Request and response bodies of the Appointments service. */
public final class AppointmentDtos {

    private AppointmentDtos() {
    }

    /** Body of POST /appointments. The patient is always the caller (from the token), never a body field. */
    public record BookRequest(
            @NotBlank @Size(max = 64) String doctorId,
            @NotBlank @Size(max = 40) String slotStart,
            @Size(max = 200) String reason) {
    }

    /** Body of PATCH /appointments/{id}/status. Only COMPLETED or NO_SHOW are accepted. */
    public record StatusRequest(@NotNull AppointmentStatus status) {
    }

    public record AppointmentResponse(
            String appointmentId,
            String patientId,
            String patientName,
            String doctorId,
            String doctorName,
            String slotStart,
            String slotStartLocal,
            AppointmentStatus status,
            String reason,
            String cancelledBy,
            String createdAt,
            String updatedAt) {
    }

    public record AppointmentPage(List<AppointmentResponse> items, String nextToken) {
    }
}
