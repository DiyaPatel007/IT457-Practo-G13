package edu.dau.practolite.users.service;

import edu.dau.practolite.common.model.TimeSupport;
import edu.dau.practolite.common.web.ApiException;
import edu.dau.practolite.users.api.dto.UserDtos.VerifyDoctorResponse;
import edu.dau.practolite.users.repo.DoctorAccountRepository;
import edu.dau.practolite.users.repo.DoctorAccountRepository.DoctorAccount;
import java.time.Clock;
import org.springframework.stereotype.Service;

/**
 * Doctor verification (endpoint #4). Anyone can register as a doctor, so a doctor stays invisible
 * in search and cannot be booked until an Admin verifies them. This models Practo's "verified doctor" idea.
 */
@Service
public class AdminService {

    private final DoctorAccountRepository doctors;
    private final Clock clock;

    public AdminService(DoctorAccountRepository doctors, Clock clock) {
        this.doctors = doctors;
        this.clock = clock;
    }

    public VerifyDoctorResponse setVerified(String doctorId, boolean verified) {
        DoctorAccount doctor = doctors.findById(doctorId)
                .orElseThrow(() -> ApiException.notFound("DOCTOR_NOT_FOUND", "No doctor with this id"));
        if (verified && (isBlank(doctor.city()) || isBlank(doctor.speciality()))) {
            throw ApiException.conflict("PROFILE_INCOMPLETE",
                    "The doctor must set city and speciality (PUT /doctors/me) before verification");
        }
        doctors.setVerification(doctor, verified, TimeSupport.toKey(clock.instant()));
        return new VerifyDoctorResponse(doctorId, verified);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
