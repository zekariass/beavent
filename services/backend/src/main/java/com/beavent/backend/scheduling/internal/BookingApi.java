package com.beavent.backend.scheduling.internal;

import com.beavent.backend.scheduling.Availability;
import com.beavent.backend.tenancy.PublicLocations;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Booking for the web layer: the request and response bodies of the booking endpoints (OpenAPI `scheduling`). */
@Component
public class BookingApi {

    public record AddonBody(UUID id, String name, int durationMinutes, long priceMinor) {}

    public record ItemBody(
            UUID variantId,
            String serviceName,
            String variantName,
            UUID staffId,
            String staffName,
            boolean staffRequested,
            Instant start,
            Instant end,
            long priceMinor,
            String currency,
            List<AddonBody> addons) {}

    public record PolicyBody(
            boolean requiresApproval,
            boolean allowPayAtVenue,
            String depositMode,
            int cancellationWindowMinutes,
            Instant freeCancellationUntil,
            BigDecimal lateCancelFeePercent,
            BigDecimal noShowFeePercent) {}

    /** What is being booked: times, people, prices (all-in), and the terms. */
    public record SummaryBody(
            UUID locationId,
            String timezone,
            String deliveryMode,
            Instant start,
            Instant end,
            List<ItemBody> items,
            long totalMinor,
            String currency,
            PolicyBody policy) {}

    public record HoldBody(UUID holdId, String holderKey, Instant expiresAt, SummaryBody booking) {}

    public record AppointmentBody(
            UUID id,
            String reference,
            String status,
            SummaryBody booking,
            @com.fasterxml.jackson.annotation.JsonIgnore boolean replayed) {}

    public record PlaceBody(
            UUID locationId,
            String businessName,
            String businessSlug,
            String locationName,
            String locationSlug,
            String landmark,
            String phone) {}

    public record MineItemBody(
            UUID variantId,
            String serviceName,
            String variantName,
            String staffName,
            Instant start,
            Instant end,
            long priceMinor,
            String currency) {}

    public record MineBody(
            UUID id,
            String reference,
            String status,
            Instant start,
            Instant end,
            String timezone,
            PlaceBody place,
            List<MineItemBody> items) {}

    public record MineList(List<MineBody> items) {}

    public record CancellationBody(boolean allowed, boolean free, Instant freeUntil, long feeMinor, String currency) {}

    public record DetailBody(
            UUID id,
            String reference,
            String status,
            Instant start,
            Instant end,
            String timezone,
            String note,
            PlaceBody place,
            SummaryBody booking,
            CancellationBody cancellation,
            boolean canReschedule) {}

    private final Bookings bookings;

    BookingApi(Bookings bookings) {
        this.bookings = bookings;
    }

    public HoldBody hold(
            UUID orgId,
            UUID locationId,
            List<Availability.Item> items,
            UUID serviceAreaId,
            Instant start,
            Instant now) {
        Bookings.Held held = bookings.hold(orgId, locationId, items, serviceAreaId, start, now);
        return new HoldBody(held.holdId(), held.holderKey(), held.expiresAt(), summary(held.plan()));
    }

    public void release(UUID holdId, String holderKey) {
        bookings.release(holdId, holderKey);
    }

    public AppointmentBody confirm(UUID orgId, UUID userId, UUID holdId, String holderKey, String note, Instant now) {
        Bookings.Booked booked = bookings.confirm(orgId, userId, holdId, holderKey, note, now);
        return new AppointmentBody(
                booked.appointmentId(), booked.reference(), booked.status(), summary(booked.plan()), booked.replayed());
    }

    /** The person's appointments, newest first, with where they are (public names, also for closed places). */
    public MineList mine(PublicLocations locations) {
        List<Bookings.Mine> mine = bookings.mine(100);
        Map<UUID, PublicLocations.Named> names = locations.names(
                new HashSet<>(mine.stream().map(Bookings.Mine::locationId).toList()));
        return new MineList(mine.stream()
                .map(m -> {
                    PublicLocations.Named n = names.get(m.locationId());
                    return new MineBody(
                            m.id(),
                            m.reference(),
                            m.status(),
                            m.start(),
                            m.end(),
                            m.timezone(),
                            n == null
                                    ? null
                                    : new PlaceBody(
                                            n.locationId(),
                                            n.businessName(),
                                            n.businessSlug(),
                                            n.locationName(),
                                            n.locationSlug(),
                                            n.landmark(),
                                            n.phone()),
                            m.items().stream()
                                    .map(i -> new MineItemBody(
                                            i.variantId(),
                                            i.serviceName(),
                                            i.variantName(),
                                            i.staffName(),
                                            i.start(),
                                            i.end(),
                                            i.priceMinor(),
                                            i.currency()))
                                    .toList());
                })
                .toList());
    }

    /** The business accepts or declines a booking request; returns the new status. */
    public String decide(UUID orgId, UUID actorUserId, UUID appointmentId, boolean accept, String reason) {
        return bookings.decide(orgId, actorUserId, appointmentId, accept, reason);
    }

    /** The business behind one of the person's appointments; call with the person's consumer context bound. */
    public java.util.Optional<UUID> orgOf(UUID appointmentId) {
        return bookings.orgOf(appointmentId);
    }

    public DetailBody detail(UUID userId, UUID appointmentId, Instant now, PublicLocations locations) {
        return detailBody(bookings.detail(userId, appointmentId, now), locations);
    }

    public DetailBody cancel(
            UUID orgId, UUID userId, UUID appointmentId, String reason, Instant now, PublicLocations locations) {
        return detailBody(bookings.cancel(orgId, userId, appointmentId, reason, now), locations);
    }

    public DetailBody reschedule(
            UUID orgId, UUID userId, UUID appointmentId, Instant start, Instant now, PublicLocations locations) {
        return detailBody(bookings.reschedule(orgId, userId, appointmentId, start, now), locations);
    }

    private static DetailBody detailBody(Bookings.Detail d, PublicLocations locations) {
        PublicLocations.Named n = locations.names(List.of(d.locationId())).get(d.locationId());
        Bookings.Cancellation c = d.cancellation();
        return new DetailBody(
                d.id(),
                d.reference(),
                d.status(),
                d.start(),
                d.end(),
                d.timezone(),
                d.note(),
                n == null
                        ? null
                        : new PlaceBody(
                                n.locationId(),
                                n.businessName(),
                                n.businessSlug(),
                                n.locationName(),
                                n.locationSlug(),
                                n.landmark(),
                                n.phone()),
                summary(d.plan()),
                new CancellationBody(c.allowed(), c.free(), c.freeUntil(), c.feeMinor(), c.currency()),
                d.canReschedule());
    }

    private static SummaryBody summary(Bookings.Plan plan) {
        Bookings.Policy p = plan.policy();
        return new SummaryBody(
                plan.locationId(),
                plan.timezone(),
                plan.deliveryMode(),
                plan.start(),
                plan.end(),
                plan.items().stream()
                        .map(i -> new ItemBody(
                                i.variantId(),
                                i.serviceName(),
                                i.variantName(),
                                i.staffId(),
                                i.staffName(),
                                i.staffRequested(),
                                i.start(),
                                i.end(),
                                i.priceMinor(),
                                i.currency(),
                                i.addons().stream()
                                        .map(a -> new AddonBody(a.id(), a.name(), a.durationMinutes(), a.priceMinor()))
                                        .toList()))
                        .toList(),
                plan.totalMinor(),
                plan.currency(),
                new PolicyBody(
                        p.requiresApproval(),
                        p.allowPayAtVenue(),
                        p.depositMode(),
                        p.cancellationWindowMinutes(),
                        p.freeCancellationUntil(),
                        p.lateCancelFeePercent(),
                        p.noShowFeePercent()));
    }
}
