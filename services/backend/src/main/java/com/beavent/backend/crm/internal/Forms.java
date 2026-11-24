package com.beavent.backend.crm.internal;

import static com.beavent.db.catalog.Tables.SERVICES;
import static com.beavent.db.crm.Tables.FORMS;
import static com.beavent.db.crm.Tables.FORM_ASSIGNMENTS;
import static com.beavent.db.crm.Tables.FORM_SUBMISSIONS;
import static com.beavent.db.crm.Tables.FORM_VERSIONS;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.Ids;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.tenancy.OrgAccess;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The business's consultation, intake and consent forms (plan step 2.6): questions in versions (a change to the
 * questions makes a new version, so earlier answers keep the questions they answered), attached to services with how
 * often a client fills them in. {@code customers.view} reads, {@code customers.manage} changes.
 */
@Component
public class Forms {

    public record Input(
            Map<String, String> title,
            String purpose,
            Map<String, String> intro,
            List<FormModel.Field> fields,
            boolean signature,
            List<UUID> serviceIds,
            String frequency,
            Integer frequencyDays,
            Boolean required) {}

    public record Body(
            UUID id,
            Map<String, String> title,
            String purpose,
            String status,
            int version,
            Map<String, String> intro,
            List<FormModel.Field> fields,
            boolean signature,
            List<UUID> serviceIds,
            String frequency,
            Integer frequencyDays,
            boolean required,
            int submissions,
            Instant updatedAt) {}

    public record Items(List<Body> items) {}

    private final DSLContext dsl;
    private final TransactionTemplate transactions;
    private final tools.jackson.databind.json.JsonMapper json;

    Forms(DSLContext dsl, TransactionTemplate transactions, tools.jackson.databind.json.JsonMapper json) {
        this.dsl = dsl;
        this.transactions = transactions;
        this.json = json;
    }

    FormModel.Schema schema(JSONB value) {
        return json.readValue(value.data(), FormModel.Schema.class);
    }

    /** Active forms first, then archived ones. */
    public Items list(OrgAccess access) {
        access.require(Clients.VIEW);
        return new Items(transactions.execute(status -> dsl
                .select(FORMS.ID)
                .from(FORMS)
                .orderBy(DSL.when(FORMS.STATUS.eq("ACTIVE"), 0).otherwise(1), FORMS.NAME, FORMS.ID)
                .fetch(FORMS.ID)
                .stream()
                .map(this::read)
                .toList()));
    }

    public Body get(OrgAccess access, UUID formId) {
        access.require(Clients.VIEW);
        return transactions.execute(status -> read(formId));
    }

    Body read(UUID formId) {
        var form = dsl.selectFrom(FORMS).where(FORMS.ID.eq(formId)).fetchOne();
        if (form == null) {
            throw new ApiException(ProblemCode.NOT_FOUND, "There is no such form.");
        }
        var version = dsl.selectFrom(FORM_VERSIONS)
                .where(FORM_VERSIONS.FORM_ID.eq(formId))
                .orderBy(FORM_VERSIONS.VERSION.desc())
                .limit(1)
                .fetchSingle();
        FormModel.Schema schema = schema(version.getSchema());
        var assignments = dsl.selectFrom(FORM_ASSIGNMENTS)
                .where(FORM_ASSIGNMENTS.FORM_ID.eq(formId), FORM_ASSIGNMENTS.SERVICE_ID.isNotNull())
                .orderBy(FORM_ASSIGNMENTS.ID)
                .fetch();
        var first = assignments.isEmpty() ? null : assignments.getFirst();
        int submissions = dsl.fetchCount(
                FORM_SUBMISSIONS,
                FORM_SUBMISSIONS.FORM_VERSION_ID.in(
                        DSL.select(FORM_VERSIONS.ID).from(FORM_VERSIONS).where(FORM_VERSIONS.FORM_ID.eq(formId))));
        return new Body(
                form.getId(),
                schema.title(),
                form.getPurpose(),
                form.getStatus(),
                version.getVersion(),
                schema.intro(),
                schema.fields(),
                schema.signature(),
                assignments.map(a -> a.getServiceId()),
                first == null ? "EVERY_TIME" : first.getFrequency(),
                first == null || first.getFrequencyDays() == null
                        ? null
                        : first.getFrequencyDays().intValue(),
                first == null || first.getIsRequired(),
                submissions,
                form.getUpdatedAt().toInstant());
    }

    public Body create(OrgAccess access, Input input) {
        access.require(Clients.MANAGE);
        FormModel.Schema schema = check(input);
        return transactions.execute(status -> {
            UUID id = Ids.newId();
            dsl.insertInto(FORMS)
                    .set(FORMS.ID, id)
                    .set(FORMS.ORG_ID, access.orgId())
                    .set(FORMS.NAME, FormModel.any(schema.title()))
                    .set(FORMS.PURPOSE, input.purpose())
                    .set(FORMS.STATUS, "ACTIVE")
                    .execute();
            newVersion(access.orgId(), id, 1, schema);
            assign(access.orgId(), id, input);
            return read(id);
        });
    }

    /** Saves the form; the questions become a new version only when they changed. */
    public Body update(OrgAccess access, UUID formId, Input input) {
        access.require(Clients.MANAGE);
        FormModel.Schema schema = check(input);
        return transactions.execute(status -> {
            var form =
                    dsl.selectFrom(FORMS).where(FORMS.ID.eq(formId)).forUpdate().fetchOne();
            if (form == null) {
                throw new ApiException(ProblemCode.NOT_FOUND, "There is no such form.");
            }
            if (form.getStatus().equals("ARCHIVED")) {
                throw new ApiException(ProblemCode.IMMUTABLE, "This form is archived.");
            }
            var latest = dsl.selectFrom(FORM_VERSIONS)
                    .where(FORM_VERSIONS.FORM_ID.eq(formId))
                    .orderBy(FORM_VERSIONS.VERSION.desc())
                    .limit(1)
                    .fetchSingle();
            if (!schema(latest.getSchema()).equals(schema)) {
                newVersion(access.orgId(), formId, latest.getVersion() + 1, schema);
            }
            dsl.update(FORMS)
                    .set(FORMS.NAME, FormModel.any(schema.title()))
                    .set(FORMS.PURPOSE, input.purpose())
                    .set(FORMS.UPDATED_AT, OffsetDateTime.now())
                    .where(FORMS.ID.eq(formId))
                    .execute();
            dsl.deleteFrom(FORM_ASSIGNMENTS)
                    .where(FORM_ASSIGNMENTS.FORM_ID.eq(formId), FORM_ASSIGNMENTS.SERVICE_ID.isNotNull())
                    .execute();
            assign(access.orgId(), formId, input);
            return read(formId);
        });
    }

    /** Archived forms are no longer asked for; answers already given stay with the clients. */
    public void archive(OrgAccess access, UUID formId) {
        access.require(Clients.MANAGE);
        int changed = transactions.execute(status -> {
            dsl.deleteFrom(FORM_ASSIGNMENTS)
                    .where(FORM_ASSIGNMENTS.FORM_ID.eq(formId))
                    .execute();
            return dsl.update(FORMS)
                    .set(FORMS.STATUS, "ARCHIVED")
                    .set(FORMS.UPDATED_AT, OffsetDateTime.now())
                    .where(FORMS.ID.eq(formId))
                    .execute();
        });
        if (changed == 0) {
            throw new ApiException(ProblemCode.NOT_FOUND, "There is no such form.");
        }
    }

    private FormModel.Schema check(Input input) {
        if (!FormModel.PURPOSES.contains(input.purpose())) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Choose what the form is for.");
        }
        String frequency = input.frequency() == null ? "EVERY_TIME" : input.frequency();
        if (!FormModel.FREQUENCIES.contains(frequency)) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Choose how often clients fill it in.");
        }
        if (frequency.equals("EVERY_N_DAYS")
                && (input.frequencyDays() == null || input.frequencyDays() < 1 || input.frequencyDays() > 3650)) {
            throw new ApiException(
                    ProblemCode.VALIDATION_FAILED, "Say after how many days (1 to 3650) it is asked again.");
        }
        return FormModel.check(new FormModel.Schema(input.title(), input.intro(), input.fields(), input.signature()));
    }

    private void newVersion(UUID orgId, UUID formId, int version, FormModel.Schema schema) {
        dsl.insertInto(FORM_VERSIONS)
                .set(FORM_VERSIONS.ID, Ids.newId())
                .set(FORM_VERSIONS.ORG_ID, orgId)
                .set(FORM_VERSIONS.FORM_ID, formId)
                .set(FORM_VERSIONS.VERSION, version)
                .set(FORM_VERSIONS.SCHEMA, JSONB.valueOf(json.writeValueAsString(schema)))
                .set(FORM_VERSIONS.PUBLISHED_AT, OffsetDateTime.now())
                .execute();
    }

    private void assign(UUID orgId, UUID formId, Input input) {
        List<UUID> serviceIds =
                new ArrayList<>(new LinkedHashSet<>(input.serviceIds() == null ? List.of() : input.serviceIds()));
        if (serviceIds.size() > 200) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Attach the form to up to 200 services.");
        }
        if (!serviceIds.isEmpty() && dsl.fetchCount(SERVICES, SERVICES.ID.in(serviceIds)) != serviceIds.size()) {
            throw new ApiException(ProblemCode.REFERENCE_NOT_FOUND, "One of the services does not exist.");
        }
        String frequency = input.frequency() == null ? "EVERY_TIME" : input.frequency();
        for (UUID serviceId : serviceIds) {
            dsl.insertInto(FORM_ASSIGNMENTS)
                    .set(FORM_ASSIGNMENTS.ID, Ids.newId())
                    .set(FORM_ASSIGNMENTS.ORG_ID, orgId)
                    .set(FORM_ASSIGNMENTS.FORM_ID, formId)
                    .set(FORM_ASSIGNMENTS.SERVICE_ID, serviceId)
                    .set(FORM_ASSIGNMENTS.TIMING, "BEFORE_VISIT")
                    .set(FORM_ASSIGNMENTS.FREQUENCY, frequency)
                    .set(
                            FORM_ASSIGNMENTS.FREQUENCY_DAYS,
                            frequency.equals("EVERY_N_DAYS")
                                    ? input.frequencyDays().shortValue()
                                    : null)
                    .set(FORM_ASSIGNMENTS.IS_REQUIRED, !Boolean.FALSE.equals(input.required()))
                    .execute();
        }
    }

    /** Titles of the forms a version list refers to (for submissions). */
    Map<UUID, FormModel.Schema> versions(List<UUID> versionIds) {
        Map<UUID, FormModel.Schema> out = new HashMap<>();
        if (versionIds.isEmpty()) return out;
        dsl.select(FORM_VERSIONS.ID, FORM_VERSIONS.SCHEMA)
                .from(FORM_VERSIONS)
                .where(FORM_VERSIONS.ID.in(versionIds))
                .forEach(r -> out.put(r.value1(), schema(r.value2())));
        return out;
    }
}
