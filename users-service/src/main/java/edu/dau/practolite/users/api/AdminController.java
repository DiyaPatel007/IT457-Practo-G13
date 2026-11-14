package edu.dau.practolite.users.api;

import edu.dau.practolite.users.api.dto.UserDtos.VerifyDoctorRequest;
import edu.dau.practolite.users.api.dto.UserDtos.VerifyDoctorResponse;
import edu.dau.practolite.users.service.AdminService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Endpoint #4. Admin only (enforced in SecurityConfig). */
@RestController
public class AdminController {

    private final AdminService service;

    public AdminController(AdminService service) {
        this.service = service;
    }

    @PatchMapping("/admin/doctors/{doctorId}/verify")
    public VerifyDoctorResponse verify(@PathVariable String doctorId, @Valid @RequestBody VerifyDoctorRequest request) {
        return service.setVerified(doctorId, request.verified());
    }
}
