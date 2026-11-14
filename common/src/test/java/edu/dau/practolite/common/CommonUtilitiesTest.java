package edu.dau.practolite.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.dau.practolite.common.model.DoctorSearchKeys;
import edu.dau.practolite.common.model.PageTokenCodec;
import edu.dau.practolite.common.model.TimeSupport;
import edu.dau.practolite.common.security.GroupsClaimParser;
import edu.dau.practolite.common.security.Role;
import edu.dau.practolite.common.web.ApiException;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

class CommonUtilitiesTest {

    @Test
    void parsesGroupsInEveryFormatApiGatewayMaySend() {
        assertThat(GroupsClaimParser.parse("[Patient]")).containsExactly(Role.PATIENT);
        assertThat(GroupsClaimParser.parse("[Admin Doctor]")).containsExactlyInAnyOrder(Role.ADMIN, Role.DOCTOR);
        assertThat(GroupsClaimParser.parse("[\"Doctor\",\"Patient\"]")).containsExactlyInAnyOrder(Role.DOCTOR, Role.PATIENT);
        assertThat(GroupsClaimParser.parse("Patient")).containsExactly(Role.PATIENT);
        assertThat(GroupsClaimParser.parse("[SuperUser]")).isEmpty();
        assertThat(GroupsClaimParser.parse(null)).isEmpty();
    }

    @Test
    void pageTokenRoundTripsAndRejectsGarbage() {
        Map<String, AttributeValue> key = Map.of("doctorId", AttributeValue.fromS("d1"),
                "slotStart", AttributeValue.fromS("2026-11-20T04:30:00Z"));
        assertThat(PageTokenCodec.decode(PageTokenCodec.encode(key))).isEqualTo(key);
        assertThat(PageTokenCodec.encode(null)).isNull();
        assertThatThrownBy(() -> PageTokenCodec.decode("%%%not-base64")).isInstanceOf(ApiException.class);
    }

    @Test
    void searchKeysAreNormalised() {
        assertThat(DoctorSearchKeys.searchCity("  New   Delhi ")).isEqualTo("new delhi");
        assertThat(DoctorSearchKeys.specKey("Cardiology", "Dr  Ravi Shah")).isEqualTo("cardiology#dr ravi shah");
        assertThat(DoctorSearchKeys.specPrefix("CARDIOLOGY")).isEqualTo("cardiology#");
    }

    @Test
    void timesAreStoredAsFixedWidthUtcKeys() {
        Instant t = TimeSupport.parseInstant("2026-11-20T10:00:00+05:30", "x");
        assertThat(TimeSupport.toKey(t)).isEqualTo("2026-11-20T04:30:00Z");
        assertThat(TimeSupport.toIst(t)).isEqualTo("2026-11-20T10:00:00+05:30");
        assertThatThrownBy(() -> TimeSupport.parseInstant("tomorrow", "x")).isInstanceOf(ApiException.class);
    }
}
