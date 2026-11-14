package edu.dau.practolite.doctors.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.dau.practolite.common.web.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SlotGridTest {

    @Test
    void istTimeIsConvertedToUtc() {
        // 10:00 IST = 04:30 UTC
        assertThat(SlotGrid.toInstant(LocalDate.parse("2026-11-20"), "10:00"))
                .isEqualTo(Instant.parse("2026-11-20T04:30:00Z"));
    }

    @Test
    void halfHourBoundariesAreOnGridInBothZones() {
        assertThat(SlotGrid.onGrid(Instant.parse("2026-11-20T04:30:00Z"))).isTrue();
        assertThat(SlotGrid.onGrid(Instant.parse("2026-11-20T05:00:00Z"))).isTrue();
        assertThat(SlotGrid.onGrid(Instant.parse("2026-11-20T05:15:00Z"))).isFalse();
    }

    @Test
    void invalidCalendarDateIsRejected() {
        assertThatThrownBy(() -> SlotGrid.parseDate("2026-02-30")).isInstanceOf(ApiException.class);
    }
}
