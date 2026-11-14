package edu.dau.practolite.users.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.dau.practolite.common.security.Role;
import edu.dau.practolite.common.web.ApiException;
import edu.dau.practolite.users.api.dto.UserDtos.RegisterRequest;
import edu.dau.practolite.users.api.dto.UserDtos.RegisterResponse;
import edu.dau.practolite.users.api.dto.UserDtos.UpdateProfileRequest;
import edu.dau.practolite.users.cognito.CognitoGateway;
import edu.dau.practolite.users.cognito.CognitoGateway.CreatedUser;
import edu.dau.practolite.users.repo.DoctorAccountRepository;
import edu.dau.practolite.users.repo.UserRecord;
import edu.dau.practolite.users.repo.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T06:00:00Z"), ZoneOffset.UTC);

    @Mock
    CognitoGateway cognito;
    @Mock
    UserRepository users;
    @Mock
    DoctorAccountRepository doctors;

    UserService service;

    @BeforeEach
    void setUp() {
        service = new UserService(cognito, users, doctors, CLOCK);
    }

    private static RegisterRequest request(Role role) {
        return new RegisterRequest(" Ravi@Example.com ", "Passw0rd!", role, " Ravi Patel ", "+919876543210");
    }

    @Test
    void registersPatientInCognitoGroupAndProfileTable() {
        when(cognito.createUser("ravi@example.com", "Ravi Patel")).thenReturn(new CreatedUser("u-name", "sub-1"));

        RegisterResponse res = service.register(request(Role.PATIENT));

        assertThat(res.userId()).isEqualTo("sub-1");
        verify(cognito).setPermanentPassword("u-name", "Passw0rd!");
        verify(cognito).addToGroup("u-name", "Patient");
        ArgumentCaptor<UserRecord> saved = ArgumentCaptor.forClass(UserRecord.class);
        verify(users).create(saved.capture());
        assertThat(saved.getValue().email()).isEqualTo("ravi@example.com");
        verify(doctors, never()).createUnverified(anyString(), anyString(), anyString());
    }

    @Test
    void registeringDoctorAlsoCreatesUnverifiedDoctorItem() {
        when(cognito.createUser(anyString(), anyString())).thenReturn(new CreatedUser("u-name", "sub-2"));
        service.register(request(Role.DOCTOR));
        verify(cognito).addToGroup("u-name", "Doctor");
        verify(doctors).createUnverified("sub-2", "Ravi Patel", "2026-11-20T06:00:00Z");
    }

    @Test
    void failureAfterCognitoUserCreationRollsBackEverything() {
        when(cognito.createUser(anyString(), anyString())).thenReturn(new CreatedUser("u-name", "sub-3"));
        doThrow(DynamoDbException.builder().message("boom").build())
                .when(doctors).createUnverified(anyString(), anyString(), anyString());

        assertThatThrownBy(() -> service.register(request(Role.DOCTOR))).isInstanceOf(DynamoDbException.class);

        verify(users).delete("sub-3");
        verify(cognito).deleteUser("u-name");
    }

    @Test
    void weakPasswordRollsBackTheCognitoUser() {
        when(cognito.createUser(anyString(), anyString())).thenReturn(new CreatedUser("u-name", "sub-4"));
        doThrow(ApiException.badRequest("WEAK_PASSWORD", "weak")).when(cognito).setPermanentPassword(any(), any());

        assertThatThrownBy(() -> service.register(request(Role.PATIENT)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("WEAK_PASSWORD");
        verify(cognito).deleteUser("u-name");
        verify(users, never()).delete(anyString());
    }

    @Test
    void adminRoleCannotBeSelfRegistered() {
        assertThatThrownBy(() -> service.register(request(Role.ADMIN)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ROLE_NOT_ALLOWED");
        verify(cognito, never()).createUser(anyString(), anyString());
    }

    @Test
    void emptyProfileUpdateIsRejected() {
        assertThatThrownBy(() -> service.update("sub-1", new UpdateProfileRequest(null, null)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("NOTHING_TO_UPDATE");
    }
}
