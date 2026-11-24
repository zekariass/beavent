package com.beavent.backend.crm.internal;

import static com.beavent.db.catalog.Tables.SERVICE_VARIANTS;
import static com.beavent.db.crm.Tables.FORMS;
import static com.beavent.db.crm.Tables.FORM_ASSIGNMENTS;
import static com.beavent.db.crm.Tables.FORM_SUBMISSIONS;
import static com.beavent.db.crm.Tables.FORM_VERSIONS;
import static com.beavent.db.scheduling.Tables.APPOINTMENTS;
import static com.beavent.db.scheduling.Tables.APPOINTMENT_ITEMS;

import com.beavent.backend.crm.AppointmentForms;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/**
 * The forms rule: a form attached to one of the visit's services is due before the visit; it counts as done when the
 * client answered it for this visit ({@code EVERY_TIME}), ever ({@code ONCE}), or within the last N days before the
 * visit ({@code EVERY_N_DAYS}). Archived forms are never due.
 */
@Component
class FormsDue implements AppointmentForms {

    private record Rule(String frequency, Integer days, boolean required) {}

    private record Answer(UUID customerId, UUID formId, UUID appointmentId, Instant at) {}

    private final DSLContext dsl;
    private final Forms forms;

    FormsDue(DSLContext dsl, Forms forms) {
        this.dsl = dsl;
        this.forms = forms;
    }

    @Override
    public Map<UUID, List<Due>> due(Collection<UUID> appointmentIds) {
        Map<UUID, List<Due>> out = new HashMap<>();
        if (appointmentIds.isEmpty()) {
            return out;
        }
        var appointments = dsl.select(APPOINTMENTS.ID, APPOINTMENTS.CUSTOMER_ID, APPOINTMENTS.STARTS_AT)
                .from(APPOINTMENTS)
                .where(APPOINTMENTS.ID.in(appointmentIds))
                .fetch();
        Map<UUID, Set<UUID>> servicesOf = new HashMap<>();
        dsl.selectDistinct(APPOINTMENT_ITEMS.APPOINTMENT_ID, SERVICE_VARIANTS.SERVICE_ID)
                .from(APPOINTMENT_ITEMS)
                .join(SERVICE_VARIANTS)
                .on(SERVICE_VARIANTS.ID.eq(APPOINTMENT_ITEMS.SERVICE_VARIANT_ID))
                .where(APPOINTMENT_ITEMS.APPOINTMENT_ID.in(appointmentIds))
                .forEach(r -> servicesOf
                        .computeIfAbsent(r.value1(), k -> new HashSet<>())
                        .add(r.value2()));
        Set<UUID> services = new HashSet<>();
        servicesOf.values().forEach(services::addAll);
        if (services.isEmpty()) {
            return out;
        }
        // Attached forms per service; a form on several services keeps its strictest rule.
        Map<UUID, Map<UUID, Rule>> formsOf = new HashMap<>();
        Map<UUID, String> purposeOf = new HashMap<>();
        dsl.select(
                        FORM_ASSIGNMENTS.SERVICE_ID,
                        FORM_ASSIGNMENTS.FORM_ID,
                        FORM_ASSIGNMENTS.FREQUENCY,
                        FORM_ASSIGNMENTS.FREQUENCY_DAYS,
                        FORM_ASSIGNMENTS.IS_REQUIRED,
                        FORMS.PURPOSE)
                .from(FORM_ASSIGNMENTS)
                .join(FORMS)
                .on(FORMS.ID.eq(FORM_ASSIGNMENTS.FORM_ID))
                .where(FORM_ASSIGNMENTS.SERVICE_ID.in(services), FORMS.STATUS.eq("ACTIVE"))
                .orderBy(FORM_ASSIGNMENTS.FORM_ID)
                .forEach(r -> {
                    formsOf.computeIfAbsent(r.value1(), k -> new LinkedHashMap<>())
                            .put(
                                    r.value2(),
                                    new Rule(
                                            r.value3(),
                                            r.value4() == null
                                                    ? null
                                                    : r.value4().intValue(),
                                            r.value5()));
                    purposeOf.put(r.value2(), r.value6());
                });
        if (formsOf.isEmpty()) {
            return out;
        }
        Set<UUID> formIds = purposeOf.keySet();
        Map<UUID, Map<String, String>> titles = new HashMap<>();
        dsl.select(FORM_VERSIONS.FORM_ID, FORM_VERSIONS.SCHEMA)
                .distinctOn(FORM_VERSIONS.FORM_ID)
                .from(FORM_VERSIONS)
                .where(FORM_VERSIONS.FORM_ID.in(formIds))
                .orderBy(FORM_VERSIONS.FORM_ID, FORM_VERSIONS.VERSION.desc())
                .forEach(r -> titles.put(r.value1(), forms.schema(r.value2()).title()));
        Set<UUID> customers = new HashSet<>(appointments.map(r -> r.value2()));
        List<Answer> answers = dsl.select(
                        FORM_SUBMISSIONS.CUSTOMER_ID,
                        FORM_VERSIONS.FORM_ID,
                        FORM_SUBMISSIONS.APPOINTMENT_ID,
                        FORM_SUBMISSIONS.SUBMITTED_AT)
                .from(FORM_SUBMISSIONS)
                .join(FORM_VERSIONS)
                .on(FORM_VERSIONS.ID.eq(FORM_SUBMISSIONS.FORM_VERSION_ID))
                .where(
                        FORM_SUBMISSIONS.CUSTOMER_ID.in(customers),
                        FORM_VERSIONS.FORM_ID.in(formIds),
                        FORM_SUBMISSIONS.STATUS.in("SUBMITTED", "REVIEWED"))
                .fetch(r -> new Answer(
                        r.value1(), r.value2(), r.value3(), r.value4().toInstant()));

        for (var a : appointments) {
            Map<UUID, Rule> rules = new LinkedHashMap<>();
            for (UUID service : servicesOf.getOrDefault(a.value1(), Set.of())) {
                formsOf.getOrDefault(service, Map.of())
                        .forEach((form, rule) -> rules.merge(form, rule, FormsDue::stricter));
            }
            if (rules.isEmpty()) {
                continue;
            }
            Instant start = a.value3().toInstant();
            List<Due> due = new ArrayList<>();
            rules.forEach((form, rule) -> {
                boolean done = answers.stream()
                        .filter(x ->
                                x.customerId().equals(a.value2()) && x.formId().equals(form))
                        .anyMatch(x -> switch (rule.frequency()) {
                            case "ONCE" -> true;
                            case "EVERY_N_DAYS" ->
                                x.appointmentId() != null && x.appointmentId().equals(a.value1())
                                        || !x.at().isBefore(start.minus(rule.days(), ChronoUnit.DAYS));
                            default -> a.value1().equals(x.appointmentId());
                        });
                due.add(new Due(form, titles.getOrDefault(form, Map.of()), purposeOf.get(form), rule.required(), done));
            });
            out.put(a.value1(), due);
        }
        return out;
    }

    private static Rule stricter(Rule a, Rule b) {
        int rankA = rank(a), rankB = rank(b);
        Rule strict = rankA >= rankB ? a : b;
        return new Rule(strict.frequency(), strict.days(), a.required() || b.required());
    }

    private static int rank(Rule r) {
        return switch (r.frequency()) {
            case "EVERY_TIME" -> 3_650_000;
            case "EVERY_N_DAYS" -> 3_650_000 - r.days();
            default -> 0;
        };
    }
}
