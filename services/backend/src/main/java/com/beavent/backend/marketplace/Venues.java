package com.beavent.backend.marketplace;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The public venue page of a location. Texts are per language ({@code {"en": …, "am": …}}); the client chooses and
 * falls back. Money is integer minor units with the currency. Call inside a transaction in the business's context.
 */
public interface Venues {

    record Image(String url, Map<String, String> variants, Map<String, String> alt) {}

    record Hours(int isoWeekday, LocalTime opensAt, LocalTime closesAt) {}

    record Closure(Instant startsAt, Instant endsAt, String reason) {}

    record Variant(
            UUID id,
            Map<String, String> name,
            int durationMinutes,
            String priceType,
            long priceMinMinor,
            long priceMaxMinor,
            String currency) {}

    record AddOn(UUID id, Map<String, String> name, int durationMinutes, long priceMinor, String currency) {}

    record Service(
            UUID id,
            Map<String, String> name,
            Map<String, String> description,
            Map<String, String> aftercare,
            String audience,
            List<String> deliveryModes,
            boolean requiresPatchTest,
            Integer patchTestValidityDays,
            List<Variant> variants,
            List<AddOn> addOns) {}

    record Group(UUID id, Map<String, String> name, List<Service> services) {}

    record Person(
            UUID id, String displayName, String jobTitle, Image photo, List<String> languages, List<UUID> serviceIds) {}

    record Policy(
            boolean requiresApproval,
            boolean allowPayAtVenue,
            String depositMode,
            java.math.BigDecimal depositPercent,
            Long depositFixedMinor,
            String depositAppliesTo,
            String currency,
            int cancellationWindowMinutes,
            java.math.BigDecimal lateCancelFeePercent,
            java.math.BigDecimal noShowFeePercent) {}

    record Other(String slug, String name, String landmark) {}

    record Venue(
            String businessSlug,
            String businessName,
            Map<String, String> description,
            Map<String, String> category,
            Image logo,
            UUID locationId,
            String locationSlug,
            String locationName,
            String phone,
            String timezone,
            String serviceMode,
            String landmark,
            String addressLine,
            String city,
            Map<String, String> area,
            Double latitude,
            Double longitude,
            boolean closedOnPublicHolidays,
            List<Hours> hours,
            List<Closure> upcomingClosures,
            boolean onlineBooking,
            boolean listed,
            List<Image> gallery,
            List<Group> groups,
            List<Person> team,
            Policy policy,
            List<Other> otherLocations) {}

    /** The page's content for {@code locationId}; the business's own context must be bound. */
    Optional<Venue> venue(UUID locationId, boolean onlineBooking, boolean listed, Instant now);
}
