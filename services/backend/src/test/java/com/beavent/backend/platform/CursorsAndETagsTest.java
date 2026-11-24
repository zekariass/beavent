package com.beavent.backend.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CursorsAndETagsTest {

    @Test
    void cursorsRoundTripAndRejectTampering() {
        String cursor = Cursors.encode("2027-01-15T17:00:00Z", "0190a8f0-0000-7000-8000-000000000001");
        assertThat(Cursors.decode(cursor, 2))
                .containsExactly("2027-01-15T17:00:00Z", "0190a8f0-0000-7000-8000-000000000001");
        assertThatThrownBy(() -> Cursors.decode(cursor, 3)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Cursors.decode("not base64 !!", 2)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Cursors.decode("", 1)).isInstanceOf(ApiException.class);
    }

    @Test
    void ifMatchMustMatchTheCurrentVersion() {
        assertThat(ETags.of(7)).isEqualTo("\"7\"");
        ETags.requireMatch("\"7\"", 7);
        ETags.requireMatch("W/\"7\"", 7);
        ETags.requireMatch("\"6\", \"7\"", 7);
        assertThatThrownBy(() -> ETags.requireMatch("\"6\"", 7))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ProblemCode.PRECONDITION_FAILED));
        assertThatThrownBy(() -> ETags.requireMatch(null, 7))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ProblemCode.PRECONDITION_REQUIRED));
    }

    @Test
    void problemCodesHaveStableTypeUris() {
        assertThat(ProblemCode.SLOT_TAKEN.type()).hasToString("urn:beavent:problem:slot-taken");
        assertThat(ProblemCode.SOLD_OUT.status().value()).isEqualTo(409);
    }
}
