package com.beavent.backend.platform.web;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error leaves the API as {@code application/problem+json} with a stable {@code code} and the
 * {@code requestId} (docs/03 §2.4).
 */
@RestControllerAdvice
public class ProblemHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApi(ApiException exception) {
        return respond(problem(exception.code(), exception.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> handleDatabase(DataAccessException exception) {
        ProblemCode code = SqlStateProblems.from(exception);
        if (code == ProblemCode.INTERNAL) {
            log.error("Unmapped database error", exception);
        } else {
            log.info(
                    "Database rule rejected request: {} ({})",
                    code,
                    exception.getMostSpecificCause().getMessage());
        }
        return respond(problem(code, code.defaultDetail()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception exception) {
        log.error("Unhandled error", exception);
        return respond(problem(ProblemCode.INTERNAL, ProblemCode.INTERNAL.defaultDetail()));
    }

    /** Framework errors (validation, unsupported media type, ...) also get a code and the request id. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(exception, body, headers, status, request);
        if (response != null && response.getBody() instanceof ProblemDetail detail) {
            if (detail.getProperties() == null || !detail.getProperties().containsKey("code")) {
                detail.setProperty(
                        "code",
                        status.is4xxClientError() ? ProblemCode.VALIDATION_FAILED.name() : ProblemCode.INTERNAL.name());
            }
            detail.setProperty("requestId", MDC.get(RequestIdFilter.MDC_KEY));
        }
        return response;
    }

    static ProblemDetail problem(ProblemCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setType(code.type());
        problem.setTitle(code.status().getReasonPhrase());
        problem.setProperty("code", code.name());
        problem.setProperty("requestId", MDC.get(RequestIdFilter.MDC_KEY));
        return problem;
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }
}
