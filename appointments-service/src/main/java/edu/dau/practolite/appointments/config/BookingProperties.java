package edu.dau.practolite.appointments.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings from application.yml "booking.*" (environment variables BOOKING_MODE, NAIVE_DELAY_MS, STRICT_TIME_RULES). */
@ConfigurationProperties(prefix = "booking")
public record BookingProperties(String mode, long naiveDelayMs, boolean strictTimeRules) {
}
