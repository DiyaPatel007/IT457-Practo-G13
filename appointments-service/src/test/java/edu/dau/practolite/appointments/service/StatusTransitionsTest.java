package edu.dau.practolite.appointments.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.dau.practolite.appointments.repo.AppointmentRecord;
import edu.dau.practolite.common.model.AppointmentStatus;
import edu.dau.practolite.common.web.ApiException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class StatusTransitionsTest {

    private static final Instant NOW = Instant.parse("2026-11-20T06:00:00Z");

    private static AppointmentRecord appt(AppointmentStatus status, String slotStart) {
        return new AppointmentRecord("a1", "p1", null, "d1", null, slotStart, status, null, null, "x", "x");
    }

    @Test
    void canCancelBookedFutureAppointment() {
        assertThatCode(() -> StatusTransitions.checkCancel(appt(AppointmentStatus.BOOKED, "2026-11-21T06:00:00Z"),
                NOW, true)).doesNotThrowAnyException();
    }

    @Test
    void cannotCancelStartedAppointmentWhenStrict() {
        assertThatThrownBy(() -> StatusTransitions.checkCancel(appt(AppointmentStatus.BOOKED, "2026-11-20T05:30:00Z"),
                NOW, true)).isInstanceOf(ApiException.class).hasMessageContaining("started");
    }

    @Test
    void cannotCancelTwice() {
        assertThatThrownBy(() -> StatusTransitions.checkCancel(appt(AppointmentStatus.CANCELLED, "2026-11-21T06:00:00Z"),
                NOW, true)).isInstanceOf(ApiException.class);
    }

    @Test
    void doctorCompletesOnlyAfterStartWhenStrict() {
        AppointmentRecord future = appt(AppointmentStatus.BOOKED, "2026-11-21T06:00:00Z");
        assertThatThrownBy(() -> StatusTransitions.checkDoctorUpdate(future, AppointmentStatus.COMPLETED, NOW, true))
                .isInstanceOf(ApiException.class).hasMessageContaining("not started");
        assertThatCode(() -> StatusTransitions.checkDoctorUpdate(future, AppointmentStatus.COMPLETED, NOW, false))
                .doesNotThrowAnyException();
    }

    @Test
    void doctorCannotSetBookedOrCancelledThroughStatusEndpoint() {
        AppointmentRecord past = appt(AppointmentStatus.BOOKED, "2026-11-19T06:00:00Z");
        assertThatThrownBy(() -> StatusTransitions.checkDoctorUpdate(past, AppointmentStatus.CANCELLED, NOW, true))
                .isInstanceOf(ApiException.class).hasMessageContaining("COMPLETED or NO_SHOW");
    }
}
