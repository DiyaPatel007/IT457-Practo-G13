package edu.dau.practolite.doctors.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.dau.practolite.common.web.ApiException;
import edu.dau.practolite.doctors.api.dto.DoctorDtos.CreateSlotsRequest;
import edu.dau.practolite.doctors.api.dto.DoctorDtos.CreateSlotsResponse;
import edu.dau.practolite.doctors.repo.DoctorRecord;
import edu.dau.practolite.doctors.repo.DoctorRepository;
import edu.dau.practolite.doctors.repo.SlotRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SlotServiceTest {

    // 11:30 IST on 20 Nov 2026
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T06:00:00Z"), ZoneOffset.UTC);
    private static final DoctorRecord DOCTOR =
            new DoctorRecord("d1", "Dr Shah", "Cardiology", "Ahmedabad", 500, null, null, null, true, "t");

    @Mock
    SlotRepository slots;
    @Mock
    DoctorRepository doctors;

    SlotService service;

    @BeforeEach
    void setUp() {
        service = new SlotService(slots, doctors, CLOCK);
    }

    @Test
    void createsFutureSlotsAndReportsPastAndDuplicates() {
        when(doctors.findById("d1")).thenReturn(Optional.of(DOCTOR));
        when(slots.createIfAbsent(eq("d1"), eq("2026-11-20T07:30:00Z"), anyString())).thenReturn(true); // 13:00 IST
        when(slots.createIfAbsent(eq("d1"), eq("2026-11-20T08:00:00Z"), anyString())).thenReturn(false); // 13:30 IST

        CreateSlotsResponse res = service.create("d1",
                new CreateSlotsRequest("2026-11-20", List.of("10:00", "13:00", "13:30", "13:00")));

        assertThat(res.created()).extracting("slotStart").containsExactly("2026-11-20T07:30:00Z");
        assertThat(res.skipped()).extracting("reason").containsExactly("PAST", "DUPLICATE");
        verify(slots, times(2)).createIfAbsent(eq("d1"), anyString(), anyString()); // repeat "13:00" sent once
    }

    @Test
    void slotsTooFarAheadAreSkipped() {
        when(doctors.findById("d1")).thenReturn(Optional.of(DOCTOR));
        CreateSlotsResponse res = service.create("d1", new CreateSlotsRequest("2027-01-30", List.of("10:00")));
        assertThat(res.skipped()).extracting("reason").containsExactly("TOO_FAR_AHEAD");
        verify(slots, never()).createIfAbsent(anyString(), anyString(), anyString());
    }

    @Test
    void listingRejectsRangesLongerThanSevenDays() {
        when(doctors.findById("d1")).thenReturn(Optional.of(DOCTOR));
        assertThatThrownBy(() -> service.listAvailable("d1", "2026-11-21T00:00:00Z", "2026-11-30T00:00:00Z"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("RANGE_TOO_LARGE");
    }

    @Test
    void unverifiedDoctorsSlotsAreHidden() {
        when(doctors.findById("d2")).thenReturn(Optional.of(
                new DoctorRecord("d2", "Dr New", null, null, null, null, null, null, false, "t")));
        assertThatThrownBy(() -> service.listAvailable("d2", null, null))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("DOCTOR_NOT_FOUND");
    }
}
