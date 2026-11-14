package edu.dau.practolite.appointments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.dau.practolite.appointments.repo.AppointmentRecord;
import edu.dau.practolite.appointments.repo.LookupRepository;
import edu.dau.practolite.appointments.repo.LookupRepository.DoctorInfo;
import edu.dau.practolite.appointments.service.BookingStrategy.BookingResult;
import edu.dau.practolite.common.model.AppointmentStatus;
import edu.dau.practolite.common.security.CurrentUser;
import edu.dau.practolite.common.security.Role;
import edu.dau.practolite.common.web.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T06:00:00Z"), ZoneOffset.UTC);
    private static final CurrentUser PATIENT = new CurrentUser("patient-1", Set.of(Role.PATIENT));

    @Mock
    BookingStrategy strategy;
    @Mock
    LookupRepository lookups;

    BookingService service;

    @BeforeEach
    void setUp() {
        service = new BookingService(strategy, lookups, CLOCK);
    }

    @Test
    void buildsBookedAppointmentFromTokenIdentityAndDoctorRecord() {
        when(lookups.findDoctor("doc-1")).thenReturn(Optional.of(new DoctorInfo("doc-1", "Dr Shah", true)));
        when(lookups.findUserName("patient-1")).thenReturn(Optional.of("Ravi"));
        when(strategy.book(any())).thenAnswer(inv -> new BookingResult(inv.getArgument(0), false));

        BookingResult result = service.book(PATIENT, "doc-1", "2026-11-21T04:30:00+00:00", " Fever ", null);

        ArgumentCaptor<AppointmentRecord> captor = ArgumentCaptor.forClass(AppointmentRecord.class);
        verify(strategy).book(captor.capture());
        AppointmentRecord a = captor.getValue();
        assertThat(a.patientId()).isEqualTo("patient-1");
        assertThat(a.doctorName()).isEqualTo("Dr Shah");
        assertThat(a.patientName()).isEqualTo("Ravi");
        assertThat(a.slotStart()).isEqualTo("2026-11-21T04:30:00Z"); // normalised key form
        assertThat(a.status()).isEqualTo(AppointmentStatus.BOOKED);
        assertThat(a.reason()).isEqualTo("Fever");
        assertThat(result.replayed()).isFalse();
    }

    @Test
    void rejectsUnverifiedDoctor() {
        when(lookups.findDoctor("doc-2")).thenReturn(Optional.of(new DoctorInfo("doc-2", "Dr New", false)));
        assertThatThrownBy(() -> service.book(PATIENT, "doc-2", "2026-11-21T04:30:00Z", null, null))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("DOCTOR_NOT_FOUND");
        verify(strategy, never()).book(any());
    }

    @Test
    void rejectsPastSlotWithoutTouchingTheDatabase() {
        assertThatThrownBy(() -> service.book(PATIENT, "doc-1", "2026-11-20T05:30:00Z", null, null))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("SLOT_IN_PAST");
        verify(lookups, never()).findDoctor(any());
    }

    @Test
    void sameIdempotencyKeyGivesSameAppointmentIdOnlyForSamePatient() {
        String a = BookingService.appointmentId("patient-1", "key-12345");
        assertThat(BookingService.appointmentId("patient-1", "key-12345")).isEqualTo(a);
        assertThat(BookingService.appointmentId("patient-2", "key-12345")).isNotEqualTo(a);
        assertThatThrownBy(() -> BookingService.appointmentId("patient-1", "bad key!"))
                .isInstanceOf(ApiException.class);
    }
}
