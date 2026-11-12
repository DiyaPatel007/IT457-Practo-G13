package edu.dau.practolite.doctors.service;

import edu.dau.practolite.common.model.Schema.Slots;
import edu.dau.practolite.common.model.TimeSupport;
import edu.dau.practolite.common.web.ApiException;
import edu.dau.practolite.doctors.api.dto.DoctorDtos.CreateSlotsRequest;
import edu.dau.practolite.doctors.api.dto.DoctorDtos.CreateSlotsResponse;
import edu.dau.practolite.doctors.api.dto.DoctorDtos.SkippedSlot;
import edu.dau.practolite.doctors.api.dto.DoctorDtos.SlotList;
import edu.dau.practolite.doctors.api.dto.DoctorDtos.SlotView;
import edu.dau.practolite.doctors.repo.DoctorRecord;
import edu.dau.practolite.doctors.repo.DoctorRepository;
import edu.dau.practolite.doctors.repo.SlotRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.stereotype.Service;

/** Slot creation, listing and deletion (endpoints #8, #9 and the optional delete). */
@Service
public class SlotService {

    private final SlotRepository slots;
    private final DoctorRepository doctors;
    private final Clock clock;

    public SlotService(SlotRepository slots, DoctorRepository doctors, Clock clock) {
        this.slots = slots;
        this.doctors = doctors;
        this.clock = clock;
    }

    /**
     * #8 Creates many slots in one call. Each slot is a separate conditional write, so one duplicate
     * does not fail the whole request: the response lists what was created and what was skipped and why.
     */
    public CreateSlotsResponse create(String doctorId, CreateSlotsRequest req) {
        if (doctors.findById(doctorId).isEmpty()) {
            throw ApiException.notFound("DOCTOR_ACCOUNT_NOT_FOUND", "Register as a doctor first");
        }
        LocalDate date = SlotGrid.parseDate(req.date());
        Instant now = clock.instant();
        Instant latest = now.plus(SlotGrid.MAX_AHEAD);
        String createdAt = TimeSupport.toKey(now);

        List<SlotView> created = new ArrayList<>();
        List<SkippedSlot> skipped = new ArrayList<>();
        for (String time : new LinkedHashSet<>(req.startTimes())) { // drop repeats inside one request
            Instant start = SlotGrid.toInstant(date, time);
            String key = TimeSupport.toKey(start);
            if (!start.isAfter(now)) {
                skipped.add(new SkippedSlot(time, key, "PAST"));
            } else if (start.isAfter(latest)) {
                skipped.add(new SkippedSlot(time, key, "TOO_FAR_AHEAD"));
            } else if (slots.createIfAbsent(doctorId, key, createdAt)) {
                created.add(view(start, "AVAILABLE"));
            } else {
                skipped.add(new SkippedSlot(time, key, "DUPLICATE"));
            }
        }
        return new CreateSlotsResponse(created, skipped);
    }

    /** #9 Public: available slots of a verified doctor between from and to (default: the next 7 days). */
    public SlotList listAvailable(String doctorId, String fromText, String toText) {
        doctors.findById(doctorId)
                .filter(DoctorRecord::verified)
                .orElseThrow(() -> ApiException.notFound("DOCTOR_NOT_FOUND", "No verified doctor with this id"));
        Instant now = clock.instant();
        Instant from = fromText == null ? now : TimeSupport.parseInstant(fromText, "from");
        if (from.isBefore(now)) {
            from = now; // past slots can never be booked, so never show them
        }
        Instant to = toText == null ? from.plus(SlotGrid.MAX_LIST_RANGE) : TimeSupport.parseInstant(toText, "to");
        if (!to.isAfter(from)) {
            throw ApiException.badRequest("INVALID_RANGE", "to must be after from (and after now)");
        }
        if (to.isAfter(from.plus(SlotGrid.MAX_LIST_RANGE))) {
            throw ApiException.badRequest("RANGE_TOO_LARGE", "The range can be at most 7 days");
        }
        List<SlotView> items = slots.findRange(doctorId, TimeSupport.toKey(from), TimeSupport.toKey(to), true)
                .stream()
                .map(s -> view(Instant.parse(s.slotStart()), s.status()))
                .toList();
        return new SlotList(doctorId, TimeSupport.toKey(from), TimeSupport.toKey(to), items);
    }

    /** Optional endpoint: delete one of the caller's own unbooked slots. */
    public void delete(String doctorId, String slotStartText) {
        Instant start = TimeSupport.parseInstant(slotStartText, "slotStart");
        if (!SlotGrid.onGrid(start)) {
            throw ApiException.notFound("SLOT_NOT_FOUND", "No slot at this time");
        }
        slots.deleteIfAvailable(doctorId, TimeSupport.toKey(start));
    }

    private static SlotView view(Instant start, String status) {
        return new SlotView(TimeSupport.toKey(start), TimeSupport.toIst(start), status, Slots.DURATION_MINUTES);
    }
}
