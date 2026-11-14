package edu.dau.practolite.doctors.service;

import edu.dau.practolite.common.model.TimeSupport;
import edu.dau.practolite.common.web.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;

/**
 * Rules for slot times. Slots are 30 minutes long and start on the hour or half hour (IST).
 * With a fixed grid two slots of the same doctor can only overlap if they have the same start,
 * which the database rejects with a conditional write.
 */
public final class SlotGrid {

    /** Doctors may open slots at most this far ahead. */
    public static final Duration MAX_AHEAD = Duration.ofDays(30);

    /** Public slot listings cover at most this range. */
    public static final Duration MAX_LIST_RANGE = Duration.ofDays(7);

    private SlotGrid() {
    }

    public static LocalDate parseDate(String date) {
        try {
            return LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("INVALID_DATE", "date must be a real calendar date, YYYY-MM-DD");
        }
    }

    /** "10:30" on a date in IST becomes the UTC instant. */
    public static Instant toInstant(LocalDate date, String hhmm) {
        LocalTime time = LocalTime.parse(hhmm);
        if (time.getMinute() % 30 != 0 || time.getSecond() != 0) {
            throw ApiException.badRequest("OFF_GRID", "Slots must start at HH:00 or HH:30");
        }
        return date.atTime(time).atZone(TimeSupport.IST).toInstant();
    }

    /** True if an arbitrary instant lies exactly on the grid (used for path parameters). */
    public static boolean onGrid(Instant instant) {
        long seconds = instant.getEpochSecond();
        return seconds % (30 * 60) == 0; // IST is UTC+5:30, so half-hour boundaries line up in both zones
    }
}
