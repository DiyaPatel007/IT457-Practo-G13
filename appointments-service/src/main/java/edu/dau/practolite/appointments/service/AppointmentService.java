package edu.dau.practolite.appointments.service;

import edu.dau.practolite.appointments.config.BookingProperties;
import edu.dau.practolite.appointments.repo.AppointmentRecord;
import edu.dau.practolite.appointments.repo.AppointmentRepository;
import edu.dau.practolite.appointments.repo.BookingTransactions;
import edu.dau.practolite.common.model.AppointmentStatus;
import edu.dau.practolite.common.model.PageTokenCodec;
import edu.dau.practolite.common.model.Schema.Appointments;
import edu.dau.practolite.common.model.TimeSupport;
import edu.dau.practolite.common.security.CurrentUser;
import edu.dau.practolite.common.security.Role;
import edu.dau.practolite.common.web.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/** Endpoints #11 to #13 and the optional GET /appointments/{id}. */
@Service
public class AppointmentService {

    static final Duration DEFAULT_PAST = Duration.ofDays(30);
    static final Duration DEFAULT_FUTURE = Duration.ofDays(60);
    static final Duration MAX_RANGE = Duration.ofDays(180);

    private final AppointmentRepository appointments;
    private final BookingTransactions transactions;
    private final BookingProperties props;
    private final Clock clock;

    public AppointmentService(AppointmentRepository appointments, BookingTransactions transactions,
                              BookingProperties props, Clock clock) {
        this.appointments = appointments;
        this.transactions = transactions;
        this.props = props;
        this.clock = clock;
    }

    /**
     * #11 One endpoint, two views: a patient sees appointments they booked (patient-index), a doctor sees
     * appointments they received (doctor-index). Which index is used depends only on the caller's role.
     */
    public Page list(CurrentUser user, String fromText, String toText, AppointmentStatus status, int limit,
                     String nextToken) {
        Instant now = clock.instant();
        Instant from = fromText == null ? now.minus(DEFAULT_PAST) : TimeSupport.parseInstant(fromText, "from");
        Instant to = toText == null ? now.plus(DEFAULT_FUTURE) : TimeSupport.parseInstant(toText, "to");
        if (!to.isAfter(from) || Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw ApiException.badRequest("INVALID_RANGE", "to must be after from, at most 180 days apart");
        }
        boolean asDoctor = user.has(Role.DOCTOR) && !user.has(Role.PATIENT);
        AppointmentRepository.Page page = appointments.listByIndex(
                asDoctor ? Appointments.DOCTOR_INDEX : Appointments.PATIENT_INDEX,
                asDoctor ? Appointments.DOCTOR_ID : Appointments.PATIENT_ID,
                user.userId(),
                TimeSupport.toKey(from), TimeSupport.toKey(to), status, limit, PageTokenCodec.decode(nextToken));
        return new Page(page.items(), PageTokenCodec.encode(page.lastEvaluatedKey()));
    }

    /** Optional: one appointment, visible only to its patient or its doctor. */
    public AppointmentRecord get(CurrentUser user, String appointmentId) {
        AppointmentRecord a = load(appointmentId);
        if (!isPatientOf(user, a) && !isDoctorOf(user, a)) {
            throw ApiException.forbidden("NOT_YOUR_APPOINTMENT", "This appointment belongs to someone else");
        }
        return a;
    }

    /** #12 Cancel and free the slot in one transaction. The patient or the doctor of the appointment may cancel. */
    public AppointmentRecord cancel(CurrentUser user, String appointmentId) {
        AppointmentRecord a = load(appointmentId);
        String by;
        if (isPatientOf(user, a)) {
            by = Role.PATIENT.name();
        } else if (isDoctorOf(user, a)) {
            by = Role.DOCTOR.name();
        } else {
            throw ApiException.forbidden("NOT_YOUR_APPOINTMENT", "This appointment belongs to someone else");
        }
        Instant now = clock.instant();
        StatusTransitions.checkCancel(a, now, props.strictTimeRules());
        String nowKey = TimeSupport.toKey(now);
        try {
            transactions.cancel(a, by, nowKey);
        } catch (TransactionCanceledException e) {
            throw TransactionErrorMapper.forCancel(e);
        }
        return new AppointmentRecord(a.appointmentId(), a.patientId(), a.patientName(), a.doctorId(), a.doctorName(),
                a.slotStart(), AppointmentStatus.CANCELLED, a.reason(), by, a.createdAt(), nowKey);
    }

    /** #13 Doctor marks their own appointment COMPLETED or NO_SHOW. */
    public AppointmentRecord updateStatus(CurrentUser user, String appointmentId, AppointmentStatus target) {
        AppointmentRecord a = load(appointmentId);
        if (!isDoctorOf(user, a)) {
            throw ApiException.forbidden("NOT_YOUR_APPOINTMENT", "Only this appointment's doctor can change its status");
        }
        Instant now = clock.instant();
        StatusTransitions.checkDoctorUpdate(a, target, now, props.strictTimeRules());
        return appointments.updateStatusIfBooked(appointmentId, user.userId(), target, TimeSupport.toKey(now));
    }

    private AppointmentRecord load(String appointmentId) {
        return appointments.findById(appointmentId)
                .orElseThrow(() -> ApiException.notFound("APPOINTMENT_NOT_FOUND", "No appointment with this id"));
    }

    private static boolean isPatientOf(CurrentUser user, AppointmentRecord a) {
        return user.has(Role.PATIENT) && user.userId().equals(a.patientId());
    }

    private static boolean isDoctorOf(CurrentUser user, AppointmentRecord a) {
        return user.has(Role.DOCTOR) && user.userId().equals(a.doctorId());
    }

    public record Page(java.util.List<AppointmentRecord> items, String nextToken) {
    }
}
