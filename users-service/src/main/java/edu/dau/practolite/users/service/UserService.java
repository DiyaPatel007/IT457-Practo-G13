package edu.dau.practolite.users.service;

import edu.dau.practolite.common.model.TimeSupport;
import edu.dau.practolite.common.security.Role;
import edu.dau.practolite.common.web.ApiException;
import edu.dau.practolite.users.api.dto.UserDtos.RegisterRequest;
import edu.dau.practolite.users.api.dto.UserDtos.RegisterResponse;
import edu.dau.practolite.users.api.dto.UserDtos.UpdateProfileRequest;
import edu.dau.practolite.users.api.dto.UserDtos.UserResponse;
import edu.dau.practolite.users.cognito.CognitoGateway;
import edu.dau.practolite.users.cognito.CognitoGateway.CreatedUser;
import edu.dau.practolite.users.repo.DoctorAccountRepository;
import edu.dau.practolite.users.repo.UserRecord;
import edu.dau.practolite.users.repo.UserRepository;
import java.time.Clock;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Registration and own-profile logic (endpoints #1 to #3). */
@Service
public class UserService {

    private static final Logger LOG = LoggerFactory.getLogger(UserService.class);

    private final CognitoGateway cognito;
    private final UserRepository users;
    private final DoctorAccountRepository doctors;
    private final Clock clock;

    public UserService(CognitoGateway cognito, UserRepository users, DoctorAccountRepository doctors, Clock clock) {
        this.cognito = cognito;
        this.users = users;
        this.doctors = doctors;
        this.clock = clock;
    }

    /**
     * Registration spans two systems (Cognito and DynamoDB), which cannot share a transaction.
     * Steps run in order; if any step after user creation fails, the earlier steps are undone
     * (compensation), so a failed registration never leaves a half-created account.
     */
    public RegisterResponse register(RegisterRequest req) {
        if (req.role() == Role.ADMIN) {
            throw ApiException.badRequest("ROLE_NOT_ALLOWED", "role must be PATIENT or DOCTOR");
        }
        String email = req.email().trim().toLowerCase(Locale.ROOT);
        String name = req.name().trim();
        String now = TimeSupport.toKey(clock.instant());

        CreatedUser created = cognito.createUser(email, name);
        boolean profileWritten = false;
        try {
            cognito.setPermanentPassword(created.username(), req.password());
            cognito.addToGroup(created.username(), req.role().cognitoGroup());
            users.create(new UserRecord(created.sub(), email, name, req.phone(), req.role(), now, now));
            profileWritten = true;
            if (req.role() == Role.DOCTOR) {
                doctors.createUnverified(created.sub(), name, now);
            }
        } catch (RuntimeException e) {
            undo(created, profileWritten);
            throw e;
        }
        return new RegisterResponse(created.sub(), email, req.role());
    }

    public UserResponse me(String userId) {
        return users.findById(userId)
                .map(UserService::toResponse)
                .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "Profile not found"));
    }

    public UserResponse update(String userId, UpdateProfileRequest req) {
        if (req.name() == null && req.phone() == null) {
            throw ApiException.badRequest("NOTHING_TO_UPDATE", "Provide name and/or phone");
        }
        String name = req.name() == null ? null : req.name().trim();
        return toResponse(users.update(userId, name, req.phone(), TimeSupport.toKey(clock.instant())));
    }

    private void undo(CreatedUser created, boolean profileWritten) {
        try {
            if (profileWritten) {
                users.delete(created.sub());
            }
            cognito.deleteUser(created.username());
        } catch (RuntimeException cleanupError) {
            // Log and keep the original error: that is the one the client must see.
            LOG.error("Registration rollback failed for sub {}: {}", created.sub(), cleanupError.toString());
        }
    }

    private static UserResponse toResponse(UserRecord u) {
        return new UserResponse(u.userId(), u.email(), u.name(), u.phone(), u.role(), u.createdAt(), u.updatedAt());
    }
}
