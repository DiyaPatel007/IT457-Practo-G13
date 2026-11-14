package edu.dau.practolite.doctors.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Request and response bodies of the Doctors service. */
public final class DoctorDtos {

    private DoctorDtos() {
    }

    /** Body of PUT /doctors/me. "verified" is deliberately absent: only an Admin can set it. */
    public record DoctorProfileRequest(
            @Size(min = 2, max = 80) String name,
            @NotBlank @Size(max = 60) String speciality,
            @NotBlank @Size(max = 60) String city,
            @NotNull @Min(1) @Max(100000) Integer fee,
            @Size(max = 120) String clinicName,
            @Min(0) @Max(70) Integer experienceYears,
            @Size(max = 500) String bio) {
    }

    public record DoctorResponse(String doctorId, String name, String speciality, String city, Integer fee,
                                 String clinicName, Integer experienceYears, String bio, boolean verified) {
    }

    public record DoctorSummary(String doctorId, String name, String speciality, String city, Integer fee,
                                String clinicName, Integer experienceYears) {
    }

    public record DoctorPage(List<DoctorSummary> items, String nextToken) {
    }

    /**
     * Body of POST /doctors/me/slots. Times are Indian Standard Time on a 30-minute grid (HH:00 or HH:30).
     * Example: {"date":"2026-11-20","startTimes":["10:00","10:30","11:00"]}
     */
    public record CreateSlotsRequest(
            @NotNull @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "must be YYYY-MM-DD") String date,
            @NotEmpty @Size(max = 48) List<
                    @NotNull @Pattern(regexp = "^([01]\\d|2[0-3]):(00|30)$",
                            message = "must be HH:00 or HH:30") String> startTimes) {
    }

    public record SlotView(String slotStart, String slotStartLocal, String status, int durationMin) {
    }

    public record SkippedSlot(String startTime, String slotStart, String reason) {
    }

    public record CreateSlotsResponse(List<SlotView> created, List<SkippedSlot> skipped) {
    }

    public record SlotList(String doctorId, String from, String to, List<SlotView> items) {
    }
}
