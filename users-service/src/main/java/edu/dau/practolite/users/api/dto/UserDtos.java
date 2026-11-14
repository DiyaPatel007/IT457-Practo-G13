package edu.dau.practolite.users.api.dto;

import edu.dau.practolite.common.security.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request and response bodies of the Users service. */
public final class UserDtos {

    private UserDtos() {
    }

    public static final String PHONE_REGEX = "^\\+?[0-9]{10,15}$";

    /** Body of POST /auth/register. Role must be PATIENT or DOCTOR (ADMIN is rejected). */
    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 64) String password,
            @NotNull Role role,
            @NotBlank @Size(min = 2, max = 80) String name,
            @Pattern(regexp = PHONE_REGEX, message = "must be 10-15 digits, optional leading +") String phone) {

        /** Never print the password, for example in logs or exception messages. */
        @Override
        public String toString() {
            return "RegisterRequest[email=" + email + ", role=" + role + ", name=" + name + "]";
        }
    }

    public record RegisterResponse(String userId, String email, Role role) {
    }

    /** Body of PATCH /users/me. At least one field must be present. */
    public record UpdateProfileRequest(
            @Size(min = 2, max = 80) String name,
            @Pattern(regexp = PHONE_REGEX, message = "must be 10-15 digits, optional leading +") String phone) {
    }

    public record UserResponse(String userId, String email, String name, String phone, Role role,
                               String createdAt, String updatedAt) {
    }

    /** Body of PATCH /admin/doctors/{doctorId}/verify. */
    public record VerifyDoctorRequest(@NotNull Boolean verified) {
    }

    public record VerifyDoctorResponse(String doctorId, boolean verified) {
    }
}
