package com.beavent.backend.tenancy.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code beavent.tenancy.*} settings. */
@ConfigurationProperties("beavent.tenancy")
public record TenancyProperties(
        /** Partner portal base URL for invitation links, e.g. https://business.beavent.com */
        String partnerPortalUrl,
        @DefaultValue("7d") Duration invitationTtl,
        @DefaultValue("50") int invitationsPerOrgPerDay) {}
