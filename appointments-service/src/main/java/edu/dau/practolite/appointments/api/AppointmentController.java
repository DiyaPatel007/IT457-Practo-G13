package edu.dau.practolite.appointments.api;

import edu.dau.practolite.appointments.api.dto.AppointmentDtos.AppointmentPage;
import edu.dau.practolite.appointments.api.dto.AppointmentDtos.AppointmentResponse;
import edu.dau.practolite.appointments.api.dto.AppointmentDtos.BookRequest;
import edu.dau.practolite.appointments.api.dto.AppointmentDtos.StatusRequest;
import edu.dau.practolite.appointments.repo.AppointmentRecord;
import edu.dau.practolite.appointments.service.AppointmentService;
import edu.dau.practolite.appointments.service.BookingService;
import edu.dau.practolite.appointments.service.BookingStrategy.BookingResult;
import edu.dau.practolite.common.model.AppointmentStatus;
import edu.dau.practolite.common.model.TimeSupport;
import edu.dau.practolite.common.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints #10 to #13 and the optional GET /appointments/{id}. */
@RestController
public class AppointmentController {

    private final BookingService booking;
    private final AppointmentService appointments;

    public AppointmentController(BookingService booking, AppointmentService appointments) {
        this.booking = booking;
        this.appointments = appointments;
    }

    /** #10 Patient: book. 201 on a new booking, 200 when an Idempotency-Key retry finds the earlier booking. */
    @PostMapping("/appointments")
    public ResponseEntity<AppointmentResponse> book(
            @AuthenticationPrincipal CurrentUser user,
            @Valid @RequestBody BookRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        BookingResult result = booking.book(user, request.doctorId(), request.slotStart(), request.reason(),
                idempotencyKey);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(toResponse(result.appointment()));
    }

    /** #11 Patient: own bookings. Doctor: received bookings. */
    @GetMapping("/appointments")
    public AppointmentPage list(@AuthenticationPrincipal CurrentUser user,
                                @RequestParam(required = false) String from,
                                @RequestParam(required = false) String to,
                                @RequestParam(required = false) AppointmentStatus status,
                                @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit,
                                @RequestParam(required = false) @Size(max = 1024) String nextToken) {
        AppointmentService.Page page = appointments.list(user, from, to, status, limit, nextToken);
        return new AppointmentPage(page.items().stream().map(AppointmentController::toResponse).toList(),
                page.nextToken());
    }

    /** Optional: one appointment (its patient or doctor only). */
    @GetMapping("/appointments/{appointmentId}")
    public AppointmentResponse get(@AuthenticationPrincipal CurrentUser user, @PathVariable String appointmentId) {
        return toResponse(appointments.get(user, appointmentId));
    }

    /** #12 Patient or doctor of the appointment: cancel and free the slot. */
    @PostMapping("/appointments/{appointmentId}/cancel")
    public AppointmentResponse cancel(@AuthenticationPrincipal CurrentUser user, @PathVariable String appointmentId) {
        return toResponse(appointments.cancel(user, appointmentId));
    }

    /** #13 Doctor of the appointment: COMPLETED or NO_SHOW. */
    @PatchMapping("/appointments/{appointmentId}/status")
    public AppointmentResponse updateStatus(@AuthenticationPrincipal CurrentUser user,
                                            @PathVariable String appointmentId,
                                            @Valid @RequestBody StatusRequest request) {
        return toResponse(appointments.updateStatus(user, appointmentId, request.status()));
    }

    static AppointmentResponse toResponse(AppointmentRecord a) {
        return new AppointmentResponse(a.appointmentId(), a.patientId(), a.patientName(), a.doctorId(),
                a.doctorName(), a.slotStart(), TimeSupport.toIst(Instant.parse(a.slotStart())), a.status(),
                a.reason(), a.cancelledBy(), a.createdAt(), a.updatedAt());
    }
}
