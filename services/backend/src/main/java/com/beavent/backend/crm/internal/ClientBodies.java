package com.beavent.backend.crm.internal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Bodies of the clients API (services/backend/src/main/openapi/crm). */
public final class ClientBodies {

    private ClientBodies() {}

    /** A row of the client list (and of the front desk's search). Phone only with contact details. */
    public record Summary(
            UUID id, String name, String phone, Instant lastVisitAt, int visits, List<String> tags, int alerts) {}

    public record Page(List<Summary> items, String nextCursor) {}

    public record Note(UUID id, String kind, String body, boolean pinned, boolean mine, Instant createdAt) {}

    public record VisitItem(
            String serviceName, String variantName, Map<String, Map<String, String>> names, String staffName) {}

    public record Visit(
            UUID id,
            String reference,
            String status,
            Instant start,
            Instant end,
            String timezone,
            UUID locationId,
            String locationName,
            List<VisitItem> items) {}

    public record Stats(int visits, int noShows, int cancellations) {}

    /** A patch test on file (valid until a date when it passed). */
    public record PatchTest(
            UUID id,
            UUID serviceId,
            Map<String, String> serviceName,
            Instant performedAt,
            String result,
            Instant validUntil,
            String notes) {}

    /** A form the client answered. */
    public record Answered(UUID id, UUID formId, Map<String, String> title, Instant submittedAt, UUID appointmentId) {}

    public record NewPatchTest(UUID serviceId, Instant performedAt, String result, String notes) {}

    public record Signature(String name, Instant signedAt) {}

    /** One set of answers with the questions they answered (that version of the form). */
    public record Submission(
            UUID id,
            UUID formId,
            Map<String, String> title,
            String purpose,
            Instant submittedAt,
            UUID appointmentId,
            List<FormModel.Field> fields,
            Map<String, Object> answers,
            Signature signature) {}

    /** What the caller may do here (so screens show only what works). */
    public record Abilities(boolean edit, boolean contactDetails) {}

    /** A client's page. Phone and email only with contact details. */
    public record Client(
            UUID id,
            String name,
            String phone,
            String email,
            String preferredLanguage,
            String source,
            Instant firstSeenAt,
            Instant lastVisitAt,
            boolean blocked,
            String blockReason,
            boolean hasAccount,
            List<String> tags,
            List<Note> notes,
            Stats stats,
            List<Visit> upcoming,
            List<Visit> past,
            List<Answered> forms,
            List<PatchTest> patchTests,
            Abilities you) {}

    public record Tag(UUID id, String name, int clients) {}

    public record Tags(List<Tag> items) {}

    public record NewClient(String name, String phone, String email, String preferredLanguage, String note) {}

    public record NewNote(String kind, String body, Boolean pinned) {}

    /** One row of an import, as read and as it would be (or was) saved. */
    public record ImportRow(int line, String name, String phone, String email, String outcome, String problem) {}

    public record ImportResult(boolean saved, int created, int existing, int errors, List<ImportRow> rows) {}
}
