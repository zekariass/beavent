package com.beavent.backend.crm.web;

import com.beavent.backend.crm.internal.MyForms;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** A client's forms before a visit (services/backend/src/main/openapi/crm): the list, the questions, the answers. */
@RestController
@Validated
class MyFormsController {

    record AnswersInput(@NotNull JsonNode answers, String signature) {}

    private final MyForms forms;

    MyFormsController(MyForms forms) {
        this.forms = forms;
    }

    private static UUID user(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    @GetMapping("/v1/me/appointments/{appointmentId}/forms")
    MyForms.DueList list(@PathVariable UUID appointmentId, @AuthenticationPrincipal Jwt jwt) {
        return forms.list(user(jwt), appointmentId);
    }

    @GetMapping("/v1/me/appointments/{appointmentId}/forms/{formId}")
    MyForms.Questions questions(
            @PathVariable UUID appointmentId, @PathVariable UUID formId, @AuthenticationPrincipal Jwt jwt) {
        return forms.questions(user(jwt), appointmentId, formId);
    }

    @PostMapping("/v1/me/appointments/{appointmentId}/forms/{formId}")
    ResponseEntity<MyForms.Submitted> submit(
            @PathVariable UUID appointmentId,
            @PathVariable UUID formId,
            @RequestBody AnswersInput body,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(forms.submit(user(jwt), appointmentId, formId, body.answers(), body.signature()));
    }
}
