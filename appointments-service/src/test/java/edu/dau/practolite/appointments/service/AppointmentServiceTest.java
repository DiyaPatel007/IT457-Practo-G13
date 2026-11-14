package edu.dau.practolite.appointments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.dau.practolite.appointments.config.BookingProperties;
import edu.dau.practolite.appointments.repo.AppointmentRecord;
import edu.dau.practolite.appointments.repo.AppointmentRepository;
import edu.dau.practolite.appointments.repo.BookingTransactions;
import edu.dau.practolite.common.model.AppointmentStatus;
import edu.dau.practolite.common.security.CurrentUser;
import edu.dau.practolite.common.security.Role;
import edu.dau.practolite.common.web.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AppointmentServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T06:00:00Z"), ZoneOffset.UTC);
    private static final CurrentUser PATIENT = new CurrentUser("p1", Set.of(Role.PATIENT));
    private static final CurrentUser DOCTOR = new CurrentUser("d1", Set.of(Role.DOCTOR));
    private static final CurrentUser OTHER_PATIENT = new CurrentUser("p2", Set.of(Role.PATIENT));
    private static final AppointmentRecord BOOKED = new AppointmentRecord("a1", "p1", "Ravi", "d1", "Dr Shah",
            "2026-11-21T04:30:00Z", AppointmentStatus.BOOKED, null, null, "t", "t");

    @Mock
    AppointmentRepository repo;
    @Mock
    BookingTransactions transactions;

    AppointmentService service;

    @BeforeEach
    void setUp() {
        service = new AppointmentService(repo, transactions, new BookingProperties("transactional", 0, true), CLOCK);
    }

    @Test
    void patientCancelsOwnAppointmentInOneTransaction() {
        when(repo.findById("a1")).thenReturn(Optional.of(BOOKED));
        AppointmentRecord result = service.cancel(PATIENT, "a1");
        verify(transactions).cancel(eq(BOOKED), eq("PATIENT"), anyString());
        assertThat(result.status()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(result.cancelledBy()).isEqualTo("PATIENT");
    }

    @Test
    void doctorOfTheAppointmentMayCancelToo() {
        when(repo.findById("a1")).thenReturn(Optional.of(BOOKED));
        assertThat(service.cancel(DOCTOR, "a1").cancelledBy()).isEqualTo("DOCTOR");
    }

    @Test
    void anotherPatientCannotCancel() {
        when(repo.findById("a1")).thenReturn(Optional.of(BOOKED));
        assertThatThrownBy(() -> service.cancel(OTHER_PATIENT, "a1"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("NOT_YOUR_APPOINTMENT");
        verify(transactions, never()).cancel(any(), any(), any());
    }

    @Test
    void doctorListUsesDoctorIndexAndPatientListUsesPatientIndex() {
        when(repo.listByIndex(anyString(), anyString(), anyString(), anyString(), anyString(), any(), eq(20), any()))
                .thenReturn(new AppointmentRepository.Page(List.of(BOOKED), null));
        service.list(DOCTOR, null, null, null, 20, null);
        verify(repo).listByIndex(eq("doctor-index"), eq("doctorId"), eq("d1"), anyString(), anyString(), any(), eq(20),
                any());
        service.list(PATIENT, null, null, null, 20, null);
        verify(repo).listByIndex(eq("patient-index"), eq("patientId"), eq("p1"), anyString(), anyString(), any(),
                eq(20), any());
    }

    @Test
    void patientCannotChangeStatus() {
        when(repo.findById("a1")).thenReturn(Optional.of(BOOKED));
        assertThatThrownBy(() -> service.updateStatus(PATIENT, "a1", AppointmentStatus.COMPLETED))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void missingAppointmentIs404() {
        when(repo.findById("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(PATIENT, "nope"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("APPOINTMENT_NOT_FOUND");
    }
}
