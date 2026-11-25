package com.beavent.backend.platform;

import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpStatus;

/** Stable, client-visible error codes (problem+json {@code code} property and {@code type} URI). */
public enum ProblemCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "The request is invalid."),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication is required."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "You do not have access to this resource."),
    STEP_UP_REQUIRED(HttpStatus.FORBIDDEN, "Please confirm it is you, then try again."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "The resource was not found."),
    CONFLICT(HttpStatus.CONFLICT, "The request conflicts with existing data."),
    SLOT_TAKEN(HttpStatus.CONFLICT, "That time is no longer available."),
    HOLD_EXPIRED(HttpStatus.CONFLICT, "The time you chose was held for a few minutes and has been released."),
    SOLD_OUT(HttpStatus.CONFLICT, "Not enough tickets are left."),
    IMMUTABLE(HttpStatus.CONFLICT, "This record can no longer be changed."),
    CONCURRENT_UPDATE(HttpStatus.CONFLICT, "Someone else changed this at the same time. Please retry."),
    IDEMPOTENCY_IN_PROGRESS(HttpStatus.CONFLICT, "A request with this Idempotency-Key is still being processed."),
    PRECONDITION_FAILED(HttpStatus.PRECONDITION_FAILED, "The resource has changed since you last read it."),
    IDEMPOTENCY_KEY_REUSED(
            HttpStatus.UNPROCESSABLE_CONTENT, "This Idempotency-Key was already used with a different request."),
    REFERENCE_NOT_FOUND(HttpStatus.UNPROCESSABLE_CONTENT, "The request refers to something that does not exist."),
    CONSTRAINT_VIOLATION(HttpStatus.UNPROCESSABLE_CONTENT, "The request breaks a business rule."),
    PATCH_TEST_REQUIRED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "This service needs a patch test at the venue first. Call them to book one."),
    PRECONDITION_REQUIRED(HttpStatus.PRECONDITION_REQUIRED, "This request requires an If-Match header."),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Please wait and try again."),
    PAYMENT_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE, "Online payment is unavailable right now. Please try again in a moment."),
    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong on our side.");

    private final HttpStatus status;
    private final String defaultDetail;

    ProblemCode(HttpStatus status, String defaultDetail) {
        this.status = status;
        this.defaultDetail = defaultDetail;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultDetail() {
        return defaultDetail;
    }

    /** e.g. {@code urn:beavent:problem:slot-taken}. */
    public URI type() {
        return URI.create(
                "urn:beavent:problem:" + name().toLowerCase(Locale.ROOT).replace('_', '-'));
    }
}
