package com.beavent.backend.identity.internal;

import static com.beavent.db.iam.Tables.AUTH_CHALLENGES;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.EmailGateway;
import com.beavent.backend.platform.IdentityDb;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.RateLimiter;
import com.beavent.backend.platform.SmsGateway;
import com.beavent.db.iam.tables.records.AuthChallengesRecord;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * One-time codes sent to a phone (SMS, Telegram, WhatsApp, voice) or an email address. Codes and destinations are
 * stored only as HMACs; each challenge allows a few
 * attempts, expires quickly and is consumed once. Requests are rate-limited per destination and per IP.
 */
@Component
public class Otps {

    public record Challenge(UUID id, Instant expiresAt, Instant resendAfter, String channel, int codeLength) {}

    /** What the verified challenge was for, with the non-secret context stored at request time. */
    public record Verified(String purpose, UUID userId, Map<String, Object> context) {
        public String phone() {
            return (String) context.get("phone");
        }

        public String email() {
            return (String) context.get("email");
        }
    }

    static final String FAILED = "The code is incorrect or has expired.";

    private final DSLContext dsl;
    private final TransactionTemplate transactions;
    private final Crypto crypto;
    private final RateLimiter rateLimiter;
    private final SmsGateway sms;
    private final EmailGateway email;
    private final IdentityProperties.Otp config;
    private final JsonMapper json;

    Otps(
            @IdentityDb DSLContext dsl,
            @IdentityDb TransactionTemplate transactions,
            Crypto crypto,
            RateLimiter rateLimiter,
            SmsGateway sms,
            EmailGateway email,
            IdentityProperties properties,
            JsonMapper json) {
        this.dsl = dsl;
        this.transactions = transactions;
        this.crypto = crypto;
        this.rateLimiter = rateLimiter;
        this.sms = sms;
        this.email = email;
        this.config = properties.otp();
        this.json = json;
    }

    /** @param phone E.164 number, or {@code null} when {@code emailAddress} is given (channel EMAIL) */
    public Challenge request(
            String phone,
            String emailAddress,
            String purpose,
            String channel,
            UUID userId,
            String ipAddress,
            String language,
            Map<String, Object> extraContext) {
        String target = phone != null ? phone : emailAddress.strip().toLowerCase(java.util.Locale.ROOT);
        String destination = crypto.destinationHash(target);
        rateLimiter.hit("otp:destination:" + destination, config.perPhonePerHour(), Duration.ofHours(1));
        if (ipAddress != null) {
            rateLimiter.hit("otp:ip:" + ipAddress, config.perIpPerHour(), Duration.ofHours(1));
        }
        String code = Crypto.randomDigits(config.codeLength());
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        Instant expires = now.plus(config.ttl());
        Map<String, Object> context = new java.util.HashMap<>(extraContext == null ? Map.of() : extraContext);
        context.put(phone != null ? "phone" : "email", target);
        transactions.executeWithoutResult(status -> {
            boolean tooSoon = dsl.fetchExists(
                    AUTH_CHALLENGES,
                    AUTH_CHALLENGES
                            .DESTINATION_HASH
                            .eq(destination)
                            .and(AUTH_CHALLENGES.CREATED_AT.gt(
                                    OffsetDateTime.ofInstant(now.minus(config.resendAfter()), ZoneOffset.UTC))));
            if (tooSoon) {
                throw new ApiException(
                        ProblemCode.RATE_LIMITED, "Please wait a moment before requesting another code.");
            }
            dsl.insertInto(AUTH_CHALLENGES)
                    .set(AUTH_CHALLENGES.ID, id)
                    .set(AUTH_CHALLENGES.USER_ID, userId)
                    .set(AUTH_CHALLENGES.PURPOSE, purpose)
                    .set(AUTH_CHALLENGES.CHANNEL, channel)
                    .set(AUTH_CHALLENGES.DESTINATION_HASH, destination)
                    .set(AUTH_CHALLENGES.CODE_HASH, crypto.otpCodeHash(id.toString(), code))
                    .set(AUTH_CHALLENGES.MAX_ATTEMPTS, (short) config.maxAttempts())
                    .set(AUTH_CHALLENGES.EXPIRES_AT, OffsetDateTime.ofInstant(expires, ZoneOffset.UTC))
                    .set(AUTH_CHALLENGES.CONTEXT, JSONB.valueOf(json.writeValueAsString(context)))
                    .execute();
        });
        if (phone != null) {
            sms.send(phone, SmsGateway.Channel.valueOf(channel), message(code, language));
        } else {
            email.send(
                    target, "am".equals(language) ? "የBeavent ማረጋገጫ ኮድ" : "Your Beavent code", message(code, language));
        }
        return new Challenge(id, expires, now.plus(config.resendAfter()), channel, config.codeLength());
    }

    /** Verifies and consumes the challenge; a wrong code still uses up an attempt (committed before failing). */
    @SuppressWarnings("unchecked")
    public Verified verify(UUID challengeId, String code, String... allowedPurposes) {
        Verified verified = transactions.execute(status -> {
            AuthChallengesRecord challenge = dsl.selectFrom(AUTH_CHALLENGES)
                    .where(AUTH_CHALLENGES.ID.eq(challengeId))
                    .forUpdate()
                    .fetchOne();
            if (challenge == null
                    || challenge.getConsumedAt() != null
                    || challenge.getExpiresAt().isBefore(OffsetDateTime.now())
                    || challenge.getAttempts() >= challenge.getMaxAttempts()
                    || !java.util.List.of(allowedPurposes).contains(challenge.getPurpose())) {
                return null;
            }
            challenge.setAttempts((short) (challenge.getAttempts() + 1));
            boolean match = Crypto.constantTimeEquals(
                    challenge.getCodeHash(), crypto.otpCodeHash(challengeId.toString(), code));
            if (match) {
                challenge.setConsumedAt(OffsetDateTime.now());
            }
            challenge.store();
            return match
                    ? new Verified(
                            challenge.getPurpose(),
                            challenge.getUserId(),
                            json.readValue(challenge.getContext().data(), Map.class))
                    : null;
        });
        if (verified == null) {
            throw new ApiException(ProblemCode.UNAUTHENTICATED, FAILED);
        }
        return verified;
    }

    private static String message(String code, String language) {
        return "am".equals(language)
                ? "የBeavent ማረጋገጫ ኮድዎ " + code + " ነው። ለማንም አያጋሩ።"
                : "Your Beavent code is " + code + ". Do not share it with anyone.";
    }
}
