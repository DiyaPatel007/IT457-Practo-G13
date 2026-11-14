package edu.dau.practolite.common.model;

import edu.dau.practolite.common.web.ApiException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * Time rules: everything is stored in UTC as a fixed-width ISO-8601 string
 * ("2026-11-20T04:30:00Z"), which sorts correctly as a DynamoDB sort key. Doctors enter
 * slot times in Indian Standard Time; responses also include the IST value for readability.
 */
public final class TimeSupport {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter UTC_KEY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneId.of("UTC"));
    private static final DateTimeFormatter IST_TEXT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX").withZone(IST);

    private TimeSupport() {
    }

    /** Canonical key form, truncated to seconds. */
    public static String toKey(Instant instant) {
        return UTC_KEY.format(instant.truncatedTo(ChronoUnit.SECONDS));
    }

    public static String toIst(Instant instant) {
        return IST_TEXT.format(instant);
    }

    /** Accepts any ISO-8601 instant with offset ("...Z" or "...+05:30"). */
    public static Instant parseInstant(String text, String field) {
        try {
            return java.time.OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException | NullPointerException e) {
            throw ApiException.badRequest("INVALID_TIME",
                    field + " must be an ISO-8601 time with offset, e.g. 2026-11-20T04:30:00Z");
        }
    }
}
