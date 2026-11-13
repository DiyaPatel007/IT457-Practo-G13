package edu.dau.practolite.appointments.service;

import edu.dau.practolite.appointments.repo.AppointmentRecord;
import edu.dau.practolite.appointments.repo.LookupRepository;
import edu.dau.practolite.appointments.repo.LookupRepository.DoctorInfo;
import edu.dau.practolite.appointments.service.BookingStrategy.BookingResult;
import edu.dau.practolite.common.model.AppointmentStatus;
import edu.dau.practolite.common.model.TimeSupport;
import edu.dau.practolite.common.security.CurrentUser;
import edu.dau.practolite.common.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Endpoint #10: book a slot. */
@Service
public class BookingService {

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    private final BookingStrategy strategy;
    private final LookupRepository lookups;
    private final Clock clock;

    public BookingService(BookingStrategy strategy, LookupRepository lookups, Clock clock) {
        this.strategy = strategy;
        this.lookups = lookups;
        this.clock = clock;
    }

    /**
     * @param idempotencyKey optional client-chosen key (Idempotency-Key header). The appointment id is
     *                       derived from (patient, key), so a retried request maps to the same id and is
     *                       recognised as a replay instead of a second booking.
     */
    public BookingResult book(CurrentUser patient, String doctorId, String slotStartText, String reason,
                              String idempotencyKey) {
        Instant slotStart = TimeSupport.parseInstant(slotStartText, "slotStart");
        Instant now = clock.instant();
        if (!slotStart.isAfter(now)) {
            throw ApiException.badRequest("SLOT_IN_PAST", "Cannot book a slot that has already started");
        }
        DoctorInfo doctor = lookups.findDoctor(doctorId)
                .filter(DoctorInfo::verified)
                .orElseThrow(() -> ApiException.notFound("DOCTOR_NOT_FOUND", "No verified doctor with this id"));
        if (doctor.doctorId().equals(patient.userId())) {
            throw ApiException.badRequest("SELF_BOOKING", "You cannot book yourself");
        }
        String patientName = lookups.findUserName(patient.userId()).orElse(null);
        String nowKey = TimeSupport.toKey(now);

        AppointmentRecord appointment = new AppointmentRecord(
                appointmentId(patient.userId(), idempotencyKey),
                patient.userId(),
                patientName,
                doctorId,
                doctor.name(),
                TimeSupport.toKey(slotStart),
                AppointmentStatus.BOOKED,
                reason == null || reason.isBlank() ? null : reason.trim(),
                null,
                nowKey,
                nowKey);
        return strategy.book(appointment);
    }

    static String appointmentId(String patientId, String idempotencyKey) {
        if (idempotencyKey == null) {
            return UUID.randomUUID().toString();
        }
        if (!IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must be 8-64 characters: letters, digits, '-' or '_'");
        }
        return UUID.nameUUIDFromBytes(("patient:" + patientId + "|key:" + idempotencyKey)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }
}
