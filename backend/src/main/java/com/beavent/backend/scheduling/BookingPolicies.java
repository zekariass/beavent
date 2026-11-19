package com.beavent.backend.scheduling;

import java.math.BigDecimal;
import java.util.UUID;

/** Booking rules in effect. Call inside a transaction with the organization's context. */
public interface BookingPolicies {

    enum Scope {
        SERVICE_AND_LOCATION,
        SERVICE,
        LOCATION,
        ORGANIZATION,
        PLATFORM_DEFAULT
    }

    record Policy(
            UUID id,
            Scope scope,
            UUID locationId,
            UUID serviceId,
            int minNoticeMinutes,
            int maxAdvanceDays,
            int slotIntervalMinutes,
            boolean requiresApproval,
            boolean allowPayAtVenue,
            String depositMode,
            BigDecimal depositPercent,
            Long depositFixedMinor,
            String depositAppliesTo,
            String currency,
            int cancellationWindowMinutes,
            BigDecimal lateCancelFeePercent,
            BigDecimal noShowFeePercent) {}

    /** The whole most specific policy: service + location, service, location, organization, platform default. */
    Policy effective(UUID locationId, UUID serviceId);
}
