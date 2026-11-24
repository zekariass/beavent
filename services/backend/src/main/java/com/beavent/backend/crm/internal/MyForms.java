package com.beavent.backend.crm.internal;

import static com.beavent.db.crm.Tables.CUSTOMERS;
import static com.beavent.db.crm.Tables.FORM_SUBMISSIONS;
import static com.beavent.db.crm.Tables.FORM_VERSIONS;
import static com.beavent.db.scheduling.Tables.APPOINTMENTS;

import com.beavent.backend.crm.AppointmentForms;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.Ids;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.RequestContext;
import com.beavent.db.scheduling.tables.records.AppointmentsRecord;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * A client filling in their forms before a visit (plan step 2.6): the forms due for one of their appointments, the
 * questions of one, and sending the answers with a typed signature. Answers can be sent again until the visit starts
 * (the newest counts; earlier ones are kept as superseded).
 */
@Component
public class MyForms {

    public record DueBody(UUID formId, Map<String, String> title, String purpose, boolean required, boolean done) {}

    public record DueList(List<DueBody> items) {}

    public record Questions(
            UUID formId,
            Map<String, String> title,
            Map<String, String> intro,
            String purpose,
            List<FormModel.Field> fields,
            boolean signature,
            boolean done) {}

    public record Submitted(UUID id, Instant submittedAt) {}

    /** Visits that can still take answers. */
    private static final Set<String> OPEN = Set.of("REQUESTED", "CONFIRMED", "ARRIVED", "IN_PROGRESS");

    private final DSLContext dsl;
    private final TransactionTemplate transactions;
    private final AppointmentForms due;
    private final Forms forms;
    private final tools.jackson.databind.json.JsonMapper json;

    MyForms(
            DSLContext dsl,
            TransactionTemplate transactions,
            AppointmentForms due,
            Forms forms,
            tools.jackson.databind.json.JsonMapper json) {
        this.dsl = dsl;
        this.transactions = transactions;
        this.due = due;
        this.forms = forms;
        this.json = json;
    }

    public DueList list(UUID userId, UUID appointmentId) {
        return inBusiness(
                userId,
                appointmentId,
                a -> new DueList(due.due(List.of(a.getId())).getOrDefault(a.getId(), List.of()).stream()
                        .map(d -> new DueBody(d.formId(), d.title(), d.purpose(), d.required(), d.done()))
                        .toList()));
    }

    public Questions questions(UUID userId, UUID appointmentId, UUID formId) {
        return inBusiness(userId, appointmentId, a -> {
            AppointmentForms.Due form = dueForm(a, formId);
            FormModel.Schema schema = latest(formId).schema();
            return new Questions(
                    formId,
                    schema.title(),
                    schema.intro(),
                    form.purpose(),
                    schema.fields(),
                    schema.signature(),
                    form.done());
        });
    }

    public Submitted submit(UUID userId, UUID appointmentId, UUID formId, JsonNode answers, String signature) {
        return inBusiness(userId, appointmentId, a -> {
            if (!OPEN.contains(a.getStatus())) {
                throw new ApiException(ProblemCode.IMMUTABLE, "This visit no longer takes answers.");
            }
            dueForm(a, formId);
            Version version = latest(formId);
            Map<String, Object> values = FormModel.answers(version.schema(), answers);
            Map<String, Object> stored = new LinkedHashMap<>();
            stored.put("values", values);
            Instant now = Instant.now();
            if (version.schema().signature()) {
                if (signature == null || signature.isBlank() || signature.length() > 120) {
                    throw new ApiException(ProblemCode.VALIDATION_FAILED, "Sign by typing your full name.");
                }
                stored.put("signature", Map.of("name", signature.strip(), "signedAt", now.toString()));
            }
            // Answers already sent for this visit are kept, marked as replaced.
            dsl.update(FORM_SUBMISSIONS)
                    .set(FORM_SUBMISSIONS.STATUS, "SUPERSEDED")
                    .where(
                            FORM_SUBMISSIONS.APPOINTMENT_ID.eq(a.getId()),
                            FORM_SUBMISSIONS.STATUS.eq("SUBMITTED"),
                            FORM_SUBMISSIONS.FORM_VERSION_ID.in(DSL.select(FORM_VERSIONS.ID)
                                    .from(FORM_VERSIONS)
                                    .where(FORM_VERSIONS.FORM_ID.eq(formId))))
                    .execute();
            UUID id = Ids.newId();
            dsl.insertInto(FORM_SUBMISSIONS)
                    .set(FORM_SUBMISSIONS.ID, id)
                    .set(FORM_SUBMISSIONS.ORG_ID, a.getOrgId())
                    .set(FORM_SUBMISSIONS.FORM_VERSION_ID, version.id())
                    .set(FORM_SUBMISSIONS.CUSTOMER_ID, a.getCustomerId())
                    .set(FORM_SUBMISSIONS.SUBMITTED_BY_USER_ID, userId)
                    .set(FORM_SUBMISSIONS.APPOINTMENT_ID, a.getId())
                    .set(FORM_SUBMISSIONS.ANSWERS, JSONB.valueOf(json.writeValueAsString(stored)))
                    .set(FORM_SUBMISSIONS.STATUS, "SUBMITTED")
                    .set(FORM_SUBMISSIONS.SUBMITTED_AT, now.atOffset(ZoneOffset.UTC))
                    .execute();
            return new Submitted(id, now);
        });
    }

    private record Version(UUID id, FormModel.Schema schema) {}

    private Version latest(UUID formId) {
        var v = dsl.select(FORM_VERSIONS.ID, FORM_VERSIONS.SCHEMA)
                .from(FORM_VERSIONS)
                .where(FORM_VERSIONS.FORM_ID.eq(formId))
                .orderBy(FORM_VERSIONS.VERSION.desc())
                .limit(1)
                .fetchSingle();
        return new Version(v.value1(), forms.schema(v.value2()));
    }

    private AppointmentForms.Due dueForm(AppointmentsRecord a, UUID formId) {
        return due.due(List.of(a.getId())).getOrDefault(a.getId(), List.of()).stream()
                .filter(d -> d.formId().equals(formId))
                .findFirst()
                .orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "This visit has no such form."));
    }

    /** Runs {@code work} in the appointment's business, for the person whose appointment it is. */
    private <T> T inBusiness(UUID userId, UUID appointmentId, java.util.function.Function<AppointmentsRecord, T> work) {
        UUID orgId = RequestContext.forUser(userId)
                .call(() -> transactions.execute(status -> dsl.select(APPOINTMENTS.ORG_ID)
                        .from(APPOINTMENTS)
                        .where(APPOINTMENTS.ID.eq(appointmentId))
                        .fetchOptional(APPOINTMENTS.ORG_ID)))
                .orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "There is no such appointment."));
        return RequestContext.forOrganization(userId, orgId)
                .call(() -> transactions.execute(status -> {
                    var a = dsl.selectFrom(APPOINTMENTS)
                            .where(
                                    APPOINTMENTS.ID.eq(appointmentId),
                                    APPOINTMENTS
                                            .BOOKED_BY_USER_ID
                                            .eq(userId)
                                            .or(APPOINTMENTS.CUSTOMER_ID.in(DSL.select(CUSTOMERS.ID)
                                                    .from(CUSTOMERS)
                                                    .where(CUSTOMERS.USER_ID.eq(userId)))))
                            .fetchOne();
                    if (a == null) {
                        throw new ApiException(ProblemCode.NOT_FOUND, "There is no such appointment.");
                    }
                    return work.apply(a);
                }));
    }
}
