package com.beavent.backend.identity.web;

import com.beavent.backend.identity.Assurance;
import com.beavent.backend.identity.internal.Accounts;
import com.beavent.backend.identity.internal.ClientContext;
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
    private final Accounts accounts;
    private final Profiles profiles;
    private final TransactionTemplate transactions;

    AuthController(
            Otps otps,
            SignIn signIn,
            Sessions sessions,
            Accounts accounts,
            Profiles profiles,
            @IdentityDb TransactionTemplate transactions) {
        this.otps = otps;
        this.signIn = signIn;
        this.sessions = sessions;
        this.accounts = accounts;
        this.profiles = profiles;
        this.transactions = transactions;
    }

    @PostMapping("/v1/auth/otp/request")
    ResponseEntity<OtpChallengeBody> requestOtp(
            @Valid @RequestBody OtpRequestBody body, @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        if ((body.phone() == null) == (body.email() == null)) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Give either a phone number or an email address.");
        }
        boolean byEmail = body.email() != null;
        String channel = byEmail ? "EMAIL" : body.channel() == null ? "SMS" : body.channel();
        if (byEmail != "EMAIL".equals(channel)) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Codes to an email address use the EMAIL channel.");
        }
        UUID userId = null;
        switch (body.purpose()) {
            case "SIGN_IN" -> {}
            case "STEP_UP" -> {
                if (jwt == null) {
                    throw new ApiException(ProblemCode.UNAUTHENTICATED);
                }
                userId = UUID.fromString(jwt.getSubject());
                var user = transactions.execute(s -> accounts.byId(UUID.fromString(jwt.getSubject()))
                        .orElseThrow(() -> new ApiException(ProblemCode.UNAUTHENTICATED)));
                boolean ownPhone = body.phone() != null
                        && user.getPhoneVerifiedAt() != null
                        && body.phone().equals(user.getPhoneE164());
                boolean ownEmail = byEmail
                        && user.getEmailVerifiedAt() != null
                        && body.email().strip().equalsIgnoreCase(user.getEmail());
                if (!ownPhone && !ownEmail) {
                    throw new ApiException(
                            ProblemCode.VALIDATION_FAILED, "Use the verified phone number or email on your account.");
                }
            }
            default -> throw new ApiException(ProblemCode.VALIDATION_FAILED, "This code purpose is not available yet.");
        }
        Otps.Challenge challenge = otps.request(
                body.phone(),
                body.email(),
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
    TokensBody verifyOtp(@Valid @RequestBody OtpVerifyBody body, HttpServletRequest request) {
        SignIn.Outcome outcome = signIn.withOtp(
                body.challengeId(),
                body.code(),
                body.device() == null ? null : body.device().toDevice(),
                request.getRemoteAddr(),
                request.getHeader("User-Agent"));
        return result(outcome);
    }

    @PostMapping("/v1/auth/oauth/{provider}")
    TokensBody signInWithProvider(
            @PathVariable String provider, @Valid @RequestBody OAuthLoginBody body, HttpServletRequest request) {
        if (!List.of("google", "apple").contains(provider)) {
            throw new ApiException(ProblemCode.NOT_FOUND);
        }
        return result(signIn.withProvider(
                provider, body.idToken(), body.nonce(), client(body.clientApp(), body.device(), request)));
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
        Otps.Verified verified = otps.verify(body.challengeId(), body.code(), "STEP_UP");
        if (!userId.equals(verified.userId())) {
            throw new ApiException(ProblemCode.UNAUTHENTICATED, "The code is incorrect or has expired.");
        }
        TokenPair tokens = transactions.execute(s -> sessions.stepUp(
                userId,
                familyId,
                new ClientContext(null, null, request.getRemoteAddr(), request.getHeader("User-Agent"))));
        return tokens(tokens, false);
    }

    private TokensBody result(SignIn.Outcome outcome) {
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
