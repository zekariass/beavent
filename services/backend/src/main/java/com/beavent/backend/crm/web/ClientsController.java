package com.beavent.backend.crm.web;

import com.beavent.backend.crm.internal.ClientBodies;
import com.beavent.backend.crm.internal.ClientImport;
import com.beavent.backend.crm.internal.ClientMerge;
import com.beavent.backend.crm.internal.Clients;
import com.beavent.backend.crm.internal.Forms;
import com.beavent.backend.crm.internal.PatchTests;
import com.beavent.backend.tenancy.OrgAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Clients API (services/backend/src/main/openapi/crm): the list, a client's page, notes, tags, merge, import. */
@RestController
@RequestMapping("/v1/orgs/{orgId}")
class ClientsController {

    record Created(UUID id) {}

    record TagsInput(@NotNull @Size(max = 20) List<@Size(max = 40) String> tags) {}

    record TagsBody(List<String> tags) {}

    record MergeInput(@NotNull UUID duplicateId) {}

    /** At most about 2 MB of text. */
    record ImportInput(@NotNull @Size(max = 2_000_000) String csv, Boolean save) {}

    private final Clients clients;
    private final ClientMerge merge;
    private final ClientImport imports;
    private final Forms forms;
    private final PatchTests patchTests;

    ClientsController(Clients clients, ClientMerge merge, ClientImport imports, Forms forms, PatchTests patchTests) {
        this.clients = clients;
        this.merge = merge;
        this.imports = imports;
        this.forms = forms;
        this.patchTests = patchTests;
    }

    @GetMapping("/customers")
    ClientBodies.Page list(
            @PathVariable UUID orgId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID tagId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return clients.list(OrgAccess.current(), q, tagId, cursor, limit);
    }

    @PostMapping("/customers")
    ResponseEntity<Created> create(@PathVariable UUID orgId, @RequestBody ClientBodies.NewClient body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(new Created(clients.create(OrgAccess.current(), body)));
    }

    @GetMapping("/customers/{customerId}")
    ClientBodies.Client get(@PathVariable UUID orgId, @PathVariable UUID customerId) {
        return clients.get(OrgAccess.current(), customerId, Instant.now());
    }

    @PatchMapping("/customers/{customerId}")
    ClientBodies.Client update(@PathVariable UUID orgId, @PathVariable UUID customerId, @RequestBody JsonNode patch) {
        return clients.update(OrgAccess.current(), customerId, patch, Instant.now());
    }

    @PostMapping("/customers/{customerId}/notes")
    ResponseEntity<ClientBodies.Note> addNote(
            @PathVariable UUID orgId, @PathVariable UUID customerId, @RequestBody ClientBodies.NewNote body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(clients.addNote(OrgAccess.current(), customerId, body));
    }

    @DeleteMapping("/customers/{customerId}/notes/{noteId}")
    ResponseEntity<Void> deleteNote(
            @PathVariable UUID orgId, @PathVariable UUID customerId, @PathVariable UUID noteId) {
        clients.deleteNote(OrgAccess.current(), customerId, noteId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/customers/{customerId}/tags")
    TagsBody setTags(@PathVariable UUID orgId, @PathVariable UUID customerId, @Valid @RequestBody TagsInput body) {
        return new TagsBody(clients.setTags(OrgAccess.current(), customerId, body.tags()));
    }

    @GetMapping("/customer-tags")
    ClientBodies.Tags tags(@PathVariable UUID orgId) {
        return clients.tags(OrgAccess.current());
    }

    @PostMapping("/customers/{customerId}/merge")
    ClientBodies.Client merge(
            @PathVariable UUID orgId, @PathVariable UUID customerId, @Valid @RequestBody MergeInput body) {
        return merge.merge(OrgAccess.current(), customerId, body.duplicateId(), Instant.now());
    }

    @PostMapping("/customers/import")
    ClientBodies.ImportResult importList(@PathVariable UUID orgId, @Valid @RequestBody ImportInput body) {
        return imports.run(OrgAccess.current(), body.csv(), Boolean.TRUE.equals(body.save()));
    }

    @GetMapping("/customers/{customerId}/form-submissions/{submissionId}")
    ClientBodies.Submission submission(
            @PathVariable UUID orgId, @PathVariable UUID customerId, @PathVariable UUID submissionId) {
        return clients.submission(OrgAccess.current(), customerId, submissionId);
    }

    @PostMapping("/customers/{customerId}/patch-tests")
    ResponseEntity<Created> recordPatchTest(
            @PathVariable UUID orgId, @PathVariable UUID customerId, @RequestBody ClientBodies.NewPatchTest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new Created(patchTests.record(OrgAccess.current(), customerId, body, Instant.now())));
    }

    @DeleteMapping("/customers/{customerId}/patch-tests/{testId}")
    ResponseEntity<Void> deletePatchTest(
            @PathVariable UUID orgId, @PathVariable UUID customerId, @PathVariable UUID testId) {
        patchTests.delete(OrgAccess.current(), customerId, testId);
        return ResponseEntity.noContent().build();
    }

    // --- forms ---------------------------------------------------------------------------------------------------

    @GetMapping("/forms")
    Forms.Items forms(@PathVariable UUID orgId) {
        return forms.list(OrgAccess.current());
    }

    @PostMapping("/forms")
    ResponseEntity<Forms.Body> createForm(@PathVariable UUID orgId, @RequestBody Forms.Input body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(forms.create(OrgAccess.current(), body));
    }

    @GetMapping("/forms/{formId}")
    Forms.Body form(@PathVariable UUID orgId, @PathVariable UUID formId) {
        return forms.get(OrgAccess.current(), formId);
    }

    @PutMapping("/forms/{formId}")
    Forms.Body updateForm(@PathVariable UUID orgId, @PathVariable UUID formId, @RequestBody Forms.Input body) {
        return forms.update(OrgAccess.current(), formId, body);
    }

    @DeleteMapping("/forms/{formId}")
    ResponseEntity<Void> archiveForm(@PathVariable UUID orgId, @PathVariable UUID formId) {
        forms.archive(OrgAccess.current(), formId);
        return ResponseEntity.noContent().build();
    }
}
