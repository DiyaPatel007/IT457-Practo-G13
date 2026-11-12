package edu.dau.practolite.doctors.api;

import edu.dau.practolite.common.security.CurrentUser;
import edu.dau.practolite.doctors.api.dto.DoctorDtos.DoctorPage;
import edu.dau.practolite.doctors.api.dto.DoctorDtos.DoctorProfileRequest;
import edu.dau.practolite.doctors.api.dto.DoctorDtos.DoctorResponse;
import edu.dau.practolite.doctors.service.DoctorService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints #5 to #7. */
@RestController
public class DoctorController {

    private final DoctorService service;

    public DoctorController(DoctorService service) {
        this.service = service;
    }

    /** #5 Doctor: create or update own profile. The doctorId is the caller's own id from the token. */
    @PutMapping("/doctors/me")
    public DoctorResponse upsertOwnProfile(@AuthenticationPrincipal CurrentUser user,
                                           @Valid @RequestBody DoctorProfileRequest request) {
        return service.upsertOwnProfile(user.userId(), request);
    }

    /** #6 Public: GET /doctors?city=Ahmedabad&speciality=Cardiology&name=shah&limit=10&nextToken=... */
    @GetMapping("/doctors")
    public DoctorPage search(@RequestParam @Size(min = 1, max = 60) String city,
                             @RequestParam(required = false) @Size(max = 60) String speciality,
                             @RequestParam(required = false) @Size(max = 60) String name,
                             @RequestParam(defaultValue = "10") @Min(1) @Max(20) int limit,
                             @RequestParam(required = false) @Size(max = 1024) String nextToken) {
        return service.search(city, speciality, name, limit, nextToken);
    }

    /** #7 Public: one verified doctor. */
    @GetMapping("/doctors/{doctorId}")
    public DoctorResponse get(@PathVariable String doctorId) {
        return service.getVerified(doctorId);
    }
}
