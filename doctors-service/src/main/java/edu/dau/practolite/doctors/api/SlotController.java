package edu.dau.practolite.doctors.api;

import edu.dau.practolite.common.security.CurrentUser;
import edu.dau.practolite.doctors.api.dto.DoctorDtos.CreateSlotsRequest;
import edu.dau.practolite.doctors.api.dto.DoctorDtos.CreateSlotsResponse;
import edu.dau.practolite.doctors.api.dto.DoctorDtos.SlotList;
import edu.dau.practolite.doctors.service.SlotService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints #8, #9 and the optional slot delete. */
@RestController
public class SlotController {

    private final SlotService service;

    public SlotController(SlotService service) {
        this.service = service;
    }

    /** #8 Doctor: create slots for one day. */
    @PostMapping("/doctors/me/slots")
    @ResponseStatus(HttpStatus.CREATED)
    public CreateSlotsResponse create(@AuthenticationPrincipal CurrentUser user,
                                      @Valid @RequestBody CreateSlotsRequest request) {
        return service.create(user.userId(), request);
    }

    /** #9 Public: GET /doctors/{id}/slots?from=2026-11-20T00:00:00Z&to=2026-11-21T00:00:00Z */
    @GetMapping("/doctors/{doctorId}/slots")
    public SlotList list(@PathVariable String doctorId,
                         @RequestParam(required = false) String from,
                         @RequestParam(required = false) String to) {
        return service.listAvailable(doctorId, from, to);
    }

    /** Optional. Doctor: DELETE /doctors/me/slots/2026-11-20T04:30:00Z (only while AVAILABLE). */
    @DeleteMapping("/doctors/me/slots/{slotStart}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal CurrentUser user, @PathVariable String slotStart) {
        service.delete(user.userId(), slotStart);
    }
}
