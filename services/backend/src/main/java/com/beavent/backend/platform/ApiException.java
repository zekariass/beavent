package com.beavent.backend.platform;

/** An expected, client-facing failure rendered as problem+json with {@link ProblemCode}. */
public class ApiException extends RuntimeException {

    private final ProblemCode code;

    public ApiException(ProblemCode code) {
        this(code, code.defaultDetail());
    }

    public ApiException(ProblemCode code, String detail) {
        super(detail);
        this.code = code;
    }

    public ProblemCode code() {
        return code;
    }
}
