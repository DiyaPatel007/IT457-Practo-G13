package edu.dau.practolite.users.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.dau.practolite.common.web.ApiException;
import edu.dau.practolite.users.repo.DoctorAccountRepository;
import edu.dau.practolite.users.repo.DoctorAccountRepository.DoctorAccount;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminServiceTest {

    @Mock
    DoctorAccountRepository doctors;

    AdminService service;

    @BeforeEach
    void setUp() {
        service = new AdminService(doctors, Clock.fixed(Instant.parse("2026-11-20T06:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void verifiesDoctorWithCompleteProfile() {
        DoctorAccount d = new DoctorAccount("d1", "Dr Shah", "Ahmedabad", "Cardiology", false, "t0");
        when(doctors.findById("d1")).thenReturn(Optional.of(d));
        service.setVerified("d1", true);
        verify(doctors).setVerification(d, true, "2026-11-20T06:00:00Z");
    }

    @Test
    void cannotVerifyDoctorWithoutCityAndSpeciality() {
        when(doctors.findById("d2")).thenReturn(Optional.of(new DoctorAccount("d2", "Dr New", null, null, false, "t0")));
        assertThatThrownBy(() -> service.setVerified("d2", true))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("PROFILE_INCOMPLETE");
        verify(doctors, never()).setVerification(any(), anyBoolean(), anyString());
    }

    @Test
    void unknownDoctorIs404() {
        when(doctors.findById("x")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.setVerified("x", true))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("DOCTOR_NOT_FOUND");
    }
}
