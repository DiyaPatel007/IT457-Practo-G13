package edu.dau.practolite.users.api;

import edu.dau.practolite.common.security.CurrentUser;
import edu.dau.practolite.users.api.dto.UserDtos.RegisterRequest;
import edu.dau.practolite.users.api.dto.UserDtos.RegisterResponse;
import edu.dau.practolite.users.api.dto.UserDtos.UpdateProfileRequest;
import edu.dau.practolite.users.api.dto.UserDtos.UserResponse;
import edu.dau.practolite.users.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints #1 to #3. The caller's identity always comes from the token ({@link CurrentUser}),
 * never from the request body.
 */
@RestController
public class UserController {

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    /** #1 Public. Creates the Cognito user, adds it to the Patient or Doctor group, writes the profile. */
    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        return service.register(request);
    }

    /** #2 Patient or Doctor: own profile. */
    @GetMapping("/users/me")
    public UserResponse me(@AuthenticationPrincipal CurrentUser user) {
        return service.me(user.userId());
    }

    /** #3 Patient or Doctor: update own name and/or phone. */
    @PatchMapping("/users/me")
    public UserResponse update(@AuthenticationPrincipal CurrentUser user,
                               @Valid @RequestBody UpdateProfileRequest request) {
        return service.update(user.userId(), request);
    }
}
