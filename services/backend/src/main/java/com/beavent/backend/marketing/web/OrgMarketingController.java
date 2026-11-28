package com.beavent.backend.marketing.web;

import com.beavent.backend.marketing.Marketing;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.tenancy.OrgAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** A business's SMS campaigns, counted links and the agreement of its clients to hear from it. */
@RestController
class OrgMarketingController {

    static final String PERMISSION = "marketing.manage";

    record CampaignInput(
            @NotBlank @Size(max = 80) String name,
            @NotNull Marketing.Audience audience,
            Integer days,
            UUID locationId,
            @NotNull Map<@Size(max = 2) String, @Size(max = Marketing.MAX_TEXT * 2) String> texts) {}

    record ScheduleInput(Instant at) {}

    record LinkInput(@NotBlank @Size(max = 80) String name, UUID locationId) {}

    record ConsentInput(@NotNull Boolean granted, @Size(max = 300) String note) {}

    record Campaigns(List<Marketing.Campaign> items) {}

    record Previews(List<Marketing.Preview> items) {}

    record Links(List<Marketing.TrackingLink> items) {}

    private final Marketing marketing;

    OrgMarketingController(Marketing marketing) {
        this.marketing = marketing;
    }

    private static OrgAccess access() {
        return OrgAccess.current().require(PERMISSION);
    }

    private static Marketing.Draft draft(CampaignInput in) {
        return new Marketing.Draft(in.name(), in.audience(), in.days(), in.locationId(), in.texts());
    }

    // --- audiences and campaigns ------------------------------------------------------------------------------

    @GetMapping("/v1/orgs/{orgId}/marketing/audience")
    Marketing.AudienceCount audience(
            @PathVariable UUID orgId,
            @RequestParam Marketing.Audience audience,
            @RequestParam(required = false) Integer days) {
        return marketing.audience(access().orgId(), audience, days);
    }

    @GetMapping("/v1/orgs/{orgId}/marketing/campaigns")
    Campaigns campaigns(@PathVariable UUID orgId) {
        return new Campaigns(marketing.campaigns(access().orgId()));
    }

    @PostMapping("/v1/orgs/{orgId}/marketing/campaigns")
    @ResponseStatus(HttpStatus.CREATED)
    Marketing.Campaign create(@PathVariable UUID orgId, @Valid @RequestBody CampaignInput body) {
        OrgAccess access = access();
        return marketing.create(access.orgId(), access.userId(), draft(body));
    }

    @GetMapping("/v1/orgs/{orgId}/marketing/campaigns/{campaignId}")
    Marketing.Campaign campaign(@PathVariable UUID orgId, @PathVariable UUID campaignId) {
        return marketing
                .campaign(access().orgId(), campaignId)
                .orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "There is no such campaign."));
    }

    @PutMapping("/v1/orgs/{orgId}/marketing/campaigns/{campaignId}")
    Marketing.Campaign update(
            @PathVariable UUID orgId, @PathVariable UUID campaignId, @Valid @RequestBody CampaignInput body) {
        return marketing.update(access().orgId(), campaignId, draft(body));
    }

    @DeleteMapping("/v1/orgs/{orgId}/marketing/campaigns/{campaignId}")
    ResponseEntity<Void> delete(@PathVariable UUID orgId, @PathVariable UUID campaignId) {
        marketing.delete(access().orgId(), campaignId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/v1/orgs/{orgId}/marketing/campaigns/{campaignId}/preview")
    Previews preview(@PathVariable UUID orgId, @PathVariable UUID campaignId) {
        return new Previews(marketing.preview(access().orgId(), campaignId));
    }

    @PostMapping("/v1/orgs/{orgId}/marketing/campaigns/{campaignId}/test")
    Marketing.Preview test(@PathVariable UUID orgId, @PathVariable UUID campaignId) {
        OrgAccess access = access();
        return marketing.testSend(access.orgId(), campaignId, access.userId());
    }

    @PostMapping("/v1/orgs/{orgId}/marketing/campaigns/{campaignId}/schedule")
    Marketing.Campaign schedule(
            @PathVariable UUID orgId, @PathVariable UUID campaignId, @RequestBody(required = false) ScheduleInput body) {
        OrgAccess access = access();
        return marketing.schedule(
                access.orgId(), campaignId, access.userId(), body == null ? null : body.at(), Instant.now());
    }

    @PostMapping("/v1/orgs/{orgId}/marketing/campaigns/{campaignId}/cancel")
    Marketing.Campaign cancel(@PathVariable UUID orgId, @PathVariable UUID campaignId) {
        OrgAccess access = access();
        return marketing.cancel(access.orgId(), campaignId, access.userId());
    }

    // --- counted links ----------------------------------------------------------------------------------------

    @GetMapping("/v1/orgs/{orgId}/marketing/links")
    Links links(@PathVariable UUID orgId) {
        return new Links(marketing.links(access().orgId()));
    }

    @PostMapping("/v1/orgs/{orgId}/marketing/links")
    @ResponseStatus(HttpStatus.CREATED)
    Marketing.TrackingLink createLink(@PathVariable UUID orgId, @Valid @RequestBody LinkInput body) {
        OrgAccess access = access();
        return marketing.createLink(access.orgId(), access.userId(), body.name(), body.locationId());
    }

    @DeleteMapping("/v1/orgs/{orgId}/marketing/links/{linkId}")
    ResponseEntity<Void> deleteLink(@PathVariable UUID orgId, @PathVariable UUID linkId) {
        marketing.deleteLink(access().orgId(), linkId);
        return ResponseEntity.noContent().build();
    }

    // --- agreement --------------------------------------------------------------------------------------------

    @GetMapping("/v1/orgs/{orgId}/customers/{customerId}/marketing-consent")
    Marketing.Consent consent(@PathVariable UUID orgId, @PathVariable UUID customerId) {
        return marketing.consent(access().orgId(), customerId);
    }

    @PutMapping("/v1/orgs/{orgId}/customers/{customerId}/marketing-consent")
    Marketing.Consent recordConsent(
            @PathVariable UUID orgId, @PathVariable UUID customerId, @Valid @RequestBody ConsentInput body) {
        OrgAccess access = access();
        return marketing.recordConsent(access.orgId(), customerId, access.userId(), body.granted(), body.note());
    }
}
