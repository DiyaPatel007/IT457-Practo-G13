package edu.dau.practolite.doctors.service;

import edu.dau.practolite.common.model.PageTokenCodec;
import edu.dau.practolite.common.model.TimeSupport;
import edu.dau.practolite.common.web.ApiException;
import edu.dau.practolite.doctors.api.dto.DoctorDtos.DoctorPage;
import edu.dau.practolite.doctors.api.dto.DoctorDtos.DoctorProfileRequest;
import edu.dau.practolite.doctors.api.dto.DoctorDtos.DoctorResponse;
import edu.dau.practolite.doctors.api.dto.DoctorDtos.DoctorSummary;
import edu.dau.practolite.doctors.repo.DoctorRecord;
import edu.dau.practolite.doctors.repo.DoctorRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;

/** Doctor profile and search logic (endpoints #5 to #7). */
@Service
public class DoctorService {

    private final DoctorRepository doctors;
    private final Clock clock;

    public DoctorService(DoctorRepository doctors, Clock clock) {
        this.doctors = doctors;
        this.clock = clock;
    }

    /** #5 Create or update the caller's own profile. The doctor item itself is created at registration. */
    public DoctorResponse upsertOwnProfile(String doctorId, DoctorProfileRequest req) {
        DoctorRecord seen = doctors.findById(doctorId)
                .orElseThrow(() -> ApiException.notFound("DOCTOR_ACCOUNT_NOT_FOUND",
                        "No doctor account for this user; register with role DOCTOR first"));
        String name = req.name() != null ? req.name().trim() : seen.name();
        DoctorRecord wanted = new DoctorRecord(doctorId, name, clean(req.speciality()), clean(req.city()),
                req.fee(), trimToNull(req.clinicName()), req.experienceYears(), trimToNull(req.bio()),
                seen.verified(), null);
        return toResponse(doctors.updateProfile(seen, wanted, TimeSupport.toKey(clock.instant())));
    }

    /** #6 Search verified doctors. City is required: it is the partition key of the search index. */
    public DoctorPage search(String city, String speciality, String name, int limit, String nextToken) {
        if (city == null || city.isBlank()) {
            throw ApiException.badRequest("CITY_REQUIRED", "city is required");
        }
        DoctorRepository.Page page = doctors.search(city, speciality, name, limit, PageTokenCodec.decode(nextToken));
        return new DoctorPage(
                page.items().stream().map(DoctorService::toSummary).toList(),
                PageTokenCodec.encode(page.lastEvaluatedKey()));
    }

    /** #7 Public doctor details. Unverified doctors are reported as not found. */
    public DoctorResponse getVerified(String doctorId) {
        return doctors.findById(doctorId)
                .filter(DoctorRecord::verified)
                .map(DoctorService::toResponse)
                .orElseThrow(() -> ApiException.notFound("DOCTOR_NOT_FOUND", "No verified doctor with this id"));
    }

    private static String clean(String s) {
        return s.trim().replaceAll("\\s+", " ");
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static DoctorResponse toResponse(DoctorRecord d) {
        return new DoctorResponse(d.doctorId(), d.name(), d.speciality(), d.city(), d.fee(), d.clinicName(),
                d.experienceYears(), d.bio(), d.verified());
    }

    private static DoctorSummary toSummary(DoctorRecord d) {
        return new DoctorSummary(d.doctorId(), d.name(), d.speciality(), d.city(), d.fee(), d.clinicName(),
                d.experienceYears());
    }
}
