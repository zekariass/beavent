package com.beavent.backend.scheduling.web;

import com.beavent.backend.scheduling.internal.Policies;
import com.beavent.backend.tenancy.OrgAccess;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Booking policies (backend/src/main/openapi/scheduling). Reads: any member; changes: org.settings.manage. */
@RestController
@RequestMapping("/v1/orgs/{orgId}/booking-policies")
class PoliciesController {

    record Items<T>(List<T> items) {}

    private final Policies policies;

    PoliciesController(Policies policies) {
        this.policies = policies;
    }

    @GetMapping
    Items<Policies.Body> list(@PathVariable UUID orgId) {
        return new Items<>(policies.list());
    }

    @PutMapping
    Policies.Body save(@PathVariable UUID orgId, @Valid @RequestBody Policies.Input body) {
        return policies.save(OrgAccess.current(), body);
    }

    @GetMapping("/effective")
    Policies.Body effective(
            @PathVariable UUID orgId,
            @RequestParam(required = false) UUID locationId,
            @RequestParam(required = false) UUID serviceId) {
        return policies.effectiveBody(locationId, serviceId);
    }

    @DeleteMapping("/{policyId}")
    ResponseEntity<Void> delete(@PathVariable UUID orgId, @PathVariable UUID policyId) {
        policies.delete(OrgAccess.current(), policyId);
        return ResponseEntity.noContent().build();
    }
}
