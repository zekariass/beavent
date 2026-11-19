package com.beavent.backend.platform.web;

import com.beavent.backend.platform.ProblemCode;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** problem+json for failures raised in the security filter chain (before MVC). */
@Component
class ProblemResponses {

    private final JsonMapper json;

    ProblemResponses(JsonMapper json) {
        this.json = json;
    }

    void write(HttpServletResponse response, ProblemCode code) throws IOException {
        ProblemDetail problem = ProblemHandler.problem(code, code.defaultDetail());
        response.setStatus(code.status().value());
        response.setContentType("application/problem+json");
        if (code == ProblemCode.UNAUTHENTICATED) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        response.getWriter().write(json.writeValueAsString(problem));
    }
}
