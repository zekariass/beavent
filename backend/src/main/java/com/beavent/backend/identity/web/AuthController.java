package com.beavent.backend.identity.web;

import com.beavent.backend.identity.Assurance;
import com.beavent.backend.identity.internal.Accounts;
import com.beavent.backend.identity.internal.ClientContext;
import com.beavent.backend.identity.internal.Mfa;
import com.beavent.backend.identity.internal.Otps;
import com.beavent.backend.identity.internal.Profiles;
import com.beavent.backend.identity.internal.Sessions;
import com.beavent.backend.identity.internal.SignIn;
import com.beavent.backend.identity.internal.TokenPair;
import com.beavent.backend.identity.web.AuthDtos.*;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.IdentityDb;
import com.beavent.backend.platform.ProblemCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AuthController {

    private final Otps otps;
    private final SignIn signIn;
    private final Sessions sessions;
    private final Mfa mfa;
    private final Accounts accounts;
    private final Profiles profiles;
    private final TransactionTemplate transactions;

    AuthController(
            Otps otps,
            SignIn signIn,
            Sessions sessions,
            Mfa mfa,
            Accounts accounts,
            Profiles profiles,
            @IdentityDb TransactionTemplate transactions) {
        this.otps = otps;
        this.signIn = signIn;
        this.sessions = sessions;
        this.mfa = mfa;
        this.accounts = accounts;
        this.profiles = profiles;
        this.transactions = transactions;
    }

    @PostMapping("/v1/auth/otp/request")
    ResponseEntity<OtpChallengeBody> requestOtp(
            @Valid @RequestBody OtpRequestBody body, @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        String channel = body.channel() == null ? "SMS" : body.channel();
        UUID userId = null;
        switch (body.purpose()) {
            case "SIGN_IN" -> {}
            case "STEP_UP" -> {
                if (jwt == null) {
                    throw new ApiException(ProblemCode.UNAUTHENTICATED);
                }
                userId = UUID.fromString(jwt.getSubject());
                String phone = transactions.execute(s -> accounts.byId(UUID.fromString(jwt.getSubject()))
                        .map(u -> u.getPhoneE164())
                        .orElse(null));
                if (phone == null || !phone.equals(body.phone())) {
                    throw new ApiException(ProblemCode.VALIDATION_FAILED, "Use the phone number on your account.");
                }
            }
            default -> throw new ApiException(ProblemCode.VALIDATION_FAILED, "This code purpose is not available yet.");
        }
        Otps.Challenge challenge = otps.request(
                body.phone(),
                body.purpose(),
                channel,
                userId,
                request.getRemoteAddr(),
                body.language(),
                Map.of("clientApp", body.clientApp(), "language", body.language() == null ? "en" : body.language()));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new OtpChallengeBody(
                        challenge.id(),
                        challenge.expiresAt(),
                        challenge.resendAfter(),
                        challenge.channel(),
                        challenge.codeLength()));
    }

    @PostMapping("/v1/auth/otp/verify")
    Object verifyOtp(@Valid @RequestBody OtpVerifyBody body, HttpServletRequest request) {
        SignIn.Outcome outcome = signIn.withOtp(
                body.challengeId(),
                body.code(),
                body.device() == null ? null : body.device().toDevice(),
                request.getRemoteAddr(),
                request.getHeader("User-Agent"));
        return result(outcome);
    }

    @PostMapping("/v1/auth/password")
    Object signInWithPassword(@Valid @RequestBody PasswordLoginBody body, HttpServletRequest request) {
        return result(signIn.withPassword(
                body.identifier(), body.password(), client(body.clientApp(), body.device(), request)));
    }

    @PostMapping("/v1/auth/oauth/{provider}")
    Object signInWithProvider(
            @PathVariable String provider, @Valid @RequestBody OAuthLoginBody body, HttpServletRequest request) {
        if (!List.of("google", "apple").contains(provider)) {
            throw new ApiException(ProblemCode.NOT_FOUND);
        }
        return result(signIn.withProvider(
                provider, body.idToken(), body.nonce(), client(body.clientApp(), body.device(), request)));
    }

    @PostMapping("/v1/auth/mfa/verify")
    TokensBody verifyMfa(@Valid @RequestBody MfaVerifyBody body) {
        return tokens(signIn.completeMfa(body.mfaChallengeId(), body.code()), false);
    }

    @PostMapping("/v1/auth/refresh")
    TokensBody refresh(@Valid @RequestBody RefreshBody body, HttpServletRequest request) {
        Sessions.RotationResult result = transactions.execute(s -> sessions.rotate(
                body.refreshToken(),
                new ClientContext(null, null, request.getRemoteAddr(), request.getHeader("User-Agent"))));
        if (result == null || result.tokens() == null) {
            throw new ApiException(ProblemCode.UNAUTHENTICATED, "Please sign in again.");
        }
        return tokens(result.tokens(), false);
    }

    @PostMapping("/v1/auth/logout")
    ResponseEntity<Void> signOut(@RequestBody(required = false) LogoutBody body, @AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        UUID familyId = UUID.fromString(jwt.getClaimAsString(Assurance.SESSION_CLAIM));
        transactions.executeWithoutResult(s -> {
            if (body != null && Boolean.TRUE.equals(body.allSessions())) {
                sessions.revokeAll(userId, null, "LOGOUT");
            } else if (body != null && body.refreshToken() != null) {
                sessions.revokeByRefreshToken(userId, body.refreshToken());
            } else {
                sessions.revokeOwnFamily(userId, familyId);
            }
        });
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/v1/auth/step-up")
    TokensBody stepUp(
            @Valid @RequestBody StepUpBody body, @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        UUID userId = UUID.fromString(jwt.getSubject());
        UUID familyId = UUID.fromString(jwt.getClaimAsString(Assurance.SESSION_CLAIM));
        if ("OTP".equals(body.method())) {
            if (body.challengeId() == null) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "challengeId is required for OTP step-up.");
            }
            Otps.Verified verified = otps.verify(body.challengeId(), body.code(), "STEP_UP");
            if (!userId.equals(verified.userId())) {
                throw new ApiException(ProblemCode.UNAUTHENTICATED, "The code is incorrect or has expired.");
            }
        } else {
            Boolean ok = transactions.execute(s -> mfa.verifyTotp(userId, body.code()));
            if (!Boolean.TRUE.equals(ok)) {
                throw new ApiException(ProblemCode.UNAUTHENTICATED, "The code is incorrect or has expired.");
            }
        }
        TokenPair tokens = transactions.execute(s -> sessions.stepUp(
                userId,
                familyId,
                new ClientContext(null, null, request.getRemoteAddr(), request.getHeader("User-Agent"))));
        return tokens(tokens, false);
    }

    private Object result(SignIn.Outcome outcome) {
        if (outcome.mfaChallengeId() != null) {
            return new MfaRequiredBody("MFA_REQUIRED", outcome.mfaChallengeId(), List.of("TOTP"));
        }
        return tokens(outcome.tokens(), outcome.newUser());
    }

    private TokensBody tokens(TokenPair pair, boolean newUser) {
        MeBody me = transactions.execute(s -> MeController.toBody(profiles.me(pair.userId())));
        return new TokensBody(
                "TOKENS",
                pair.accessToken(),
                pair.accessTokenExpiresAt(),
                pair.refreshToken(),
                pair.refreshTokenExpiresAt(),
                newUser,
                me);
    }

    private static ClientContext client(String clientApp, DeviceInfo device, HttpServletRequest request) {
        return new ClientContext(
                clientApp,
                device == null ? null : device.toDevice(),
                request.getRemoteAddr(),
                request.getHeader("User-Agent"));
    }
}
