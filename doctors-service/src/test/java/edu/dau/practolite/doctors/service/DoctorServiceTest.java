package edu.dau.practolite.doctors.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.dau.practolite.common.web.ApiException;
import edu.dau.practolite.doctors.api.dto.DoctorDtos.DoctorProfileRequest;
import edu.dau.practolite.doctors.repo.DoctorRecord;
import edu.dau.practolite.doctors.repo.DoctorRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DoctorServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T06:00:00Z"), ZoneOffset.UTC);

    @Mock
    DoctorRepository repo;

    DoctorService service;

    @BeforeEach
    void setUp() {
        service = new DoctorService(repo, CLOCK);
    }

    @Test
    void profileUpdateKeepsVerificationAndCleansInput() {
        DoctorRecord seen = new DoctorRecord("d1", "Dr Shah", null, null, null, null, null, null, true, "t0");
        when(repo.findById("d1")).thenReturn(Optional.of(seen));
        when(repo.updateProfile(any(), any(), anyString())).thenAnswer(inv -> inv.getArgument(1));

        service.upsertOwnProfile("d1", new DoctorProfileRequest(null, " Cardiology ", "  New   Delhi ", 700,
                "  ", 12, null));

        ArgumentCaptor<DoctorRecord> wanted = ArgumentCaptor.forClass(DoctorRecord.class);
        verify(repo).updateProfile(eq(seen), wanted.capture(), eq("2026-11-20T06:00:00Z"));
        assertThat(wanted.getValue().name()).isEqualTo("Dr Shah");
        assertThat(wanted.getValue().city()).isEqualTo("New Delhi");
        assertThat(wanted.getValue().clinicName()).isNull();
        assertThat(wanted.getValue().verified()).isTrue();
    }

    @Test
    void patientWithoutDoctorAccountCannotCreateProfile() {
        when(repo.findById("p1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.upsertOwnProfile("p1",
                new DoctorProfileRequest(null, "Cardiology", "Ahmedabad", 500, null, null, null)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("DOCTOR_ACCOUNT_NOT_FOUND");
    }

    @Test
    void unverifiedDoctorIsNotPubliclyVisible() {
        when(repo.findById("d2")).thenReturn(Optional.of(
                new DoctorRecord("d2", "Dr New", "ENT", "Surat", 300, null, null, null, false, "t")));
        assertThatThrownBy(() -> service.getVerified("d2")).isInstanceOf(ApiException.class);
    }

    @Test
    void searchNeedsCity() {
        assertThatThrownBy(() -> service.search(" ", null, null, 10, null))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("CITY_REQUIRED");
    }
}
