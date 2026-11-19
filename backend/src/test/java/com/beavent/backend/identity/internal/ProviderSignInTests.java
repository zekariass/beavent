package com.beavent.backend.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.beavent.backend.ApiTestSupport;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Google/Apple sign-in account rules, with a stub verifier (token "subject|email"); real token verification is
 * covered by {@link IdTokenVerifierTest}.
 */
@Import(ProviderSignInTests.StubVerifier.class)
class ProviderSignInTests extends ApiTestSupport {

    @TestConfiguration
    static class StubVerifier {
        @Bean
        @Primary
        IdTokenVerifier stubIdTokenVerifier() {
            return (provider, idToken, nonce) -> {
                if (!"google".equals(provider)) {
                    throw new ApiException(ProblemCode.NOT_FOUND);
                }
                String[] parts = idToken.split("\\|");
                return new IdTokenVerifier.VerifiedIdentity(provider, parts[0], parts[1], true);
            };
        }
    }

    Call google(String subject, String email) throws Exception {
        return call(
                post("/v1/auth/oauth/google"),
                Map.of("idToken", subject + "|" + email, "nonce", "n", "clientApp", "MOBILE_APP"),
                null);
    }

    @Test
    void googleSignInCreatesOrJoinsTheAccountWithThatVerifiedEmailButNeverAnUnverifiedOne() throws Exception {
        String address = "abebe." + UUID.randomUUID().toString().substring(0, 8) + "@gmail.com";
        Call first = google("g-" + address, address);
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body().get("isNewUser").asBoolean()).isTrue();
        assertThat(claims(json, first.text("accessToken")).get("aal").asString())
                .isEqualTo("PROVIDER");
        Call again = google("g-" + address, address);
        assertThat(again.body().get("isNewUser").asBoolean()).isFalse();
        assertThat(again.body().get("user").get("id").asString())
                .isEqualTo(first.body().get("user").get("id").asString());

        // A phone account that only typed this (other) address does not get opened by a Google sign-in.
        String typed = "typed." + UUID.randomUUID().toString().substring(0, 8) + "@gmail.com";
        String phoneUser = signInWithOtp(newPhone()).text("accessToken");
        call(patch("/v1/me"), Map.of("email", typed), phoneUser);
        Call other = google("g-" + typed, typed);
        assertThat(other.body().get("isNewUser").asBoolean()).isTrue();

        assertThat(call(
                                post("/v1/auth/oauth/apple"),
                                Map.of("idToken", "x|y@z.com", "nonce", "n", "clientApp", "MOBILE_APP"),
                                null)
                        .status())
                .isEqualTo(404);
    }
}
