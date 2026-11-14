package edu.dau.practolite.doctors.repo;

/** One item of the Doctors table. Profile fields are null until the doctor calls PUT /doctors/me. */
public record DoctorRecord(String doctorId, String name, String speciality, String city, Integer fee,
                           String clinicName, Integer experienceYears, String bio, boolean verified,
                           String updatedAt) {
}
