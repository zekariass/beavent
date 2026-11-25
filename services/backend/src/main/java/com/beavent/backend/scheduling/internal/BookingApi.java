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

    /**
     * What the customer would pay now to book (a person not signed in yet counts as new), and what is left for the
     * venue; null when nothing is due online.
     */
    public record PaymentTermsBody(long depositMinor, long atVenueMinor, long totalMinor, String currency) {}

    /** Where an unpaid booking's online payment stands (docs/04 §6). */
    public record PaymentBody(
            String state,
            long dueNowMinor,
            long paidMinor,
            long atVenueMinor,
            String currency,
            Instant expiresAt,
            String redirectUrl) {}

    /** The provider's page to send the person to, and when it stops accepting payment. */
    public record PaymentStartBody(
            UUID attemptId, String redirectUrl, Instant expiresAt, long amountMinor, String currency) {}

    public record HoldBody(
            UUID holdId, String holderKey, Instant expiresAt, SummaryBody booking, PaymentTermsBody payment) {}

    public record AppointmentBody(
            UUID id,
            String reference,
            String status,
            SummaryBody booking,
            PaymentBody payment,
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

    public record CancellationBody(
            boolean allowed,
            boolean free,
            Instant freeUntil,
            long feeMinor,
            String currency,
            long refundMinor,
            long keptMinor) {}

    /** What is refunded for a cancelled booking: {@code ON_THE_WAY}, {@code SENT}, or {@code NONE} (all of it kept). */
    public record RefundBody(long amountMinor, String currency, String state) {}

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
            boolean canReschedule,
            PaymentBody payment,
            RefundBody refund) {}

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
        DepositRules.Terms t = held.payment();
        return new HoldBody(
                held.holdId(),
                held.holderKey(),
                held.expiresAt(),
                summary(held.plan()),
                t == null
                        ? null
                        : new PaymentTermsBody(
                                t.depositMinor(),
                                t.atVenueMinor(),
                                t.totalMinor(),
                                held.plan().currency()));
    }

    public void release(UUID holdId, String holderKey) {
        bookings.release(holdId, holderKey);
    }

    public AppointmentBody confirm(UUID orgId, UUID userId, UUID holdId, String holderKey, String note, Instant now) {
        Bookings.Booked booked = bookings.confirm(orgId, userId, holdId, holderKey, note, now);
        return new AppointmentBody(
                booked.appointmentId(),
                booked.reference(),
                booked.status(),
                summary(booked.plan()),
                paymentBody(booked.payment()),
                booked.replayed());
    }

    private static PaymentBody paymentBody(com.beavent.backend.payments.Payments.Status p) {
        return p == null
                ? null
                : new PaymentBody(
                        p.state(),
                        p.dueNowMinor(),
                        p.paidMinor(),
                        p.atVenueMinor(),
                        p.currency(),
                        p.expiresAt(),
                        p.redirectUrl() == null ? null : p.redirectUrl().toString());
    }

    /** Starts (or resumes) the online payment of the person's unpaid booking. */
    public PaymentStartBody startPayment(UUID orgId, UUID userId, UUID appointmentId, String language, Instant now) {
        var started = bookings.startPayment(orgId, userId, appointmentId, language, now);
        return new PaymentStartBody(
                started.attemptId(),
                started.redirectUrl() == null ? null : started.redirectUrl().toString(),
                started.expiresAt(),
                started.amountMinor(),
                started.currency());
    }

    /** The person came back from paying: asks the provider, then shows where the booking stands. */
    public DetailBody checkPayment(
            UUID orgId, UUID userId, UUID appointmentId, Instant now, PublicLocations locations) {
        return detailBody(bookings.checkPayment(orgId, userId, appointmentId, now), locations);
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

    /**
     * A desk booking: the first appointment's id, reference and time (as before), and for a group or a series every
     * appointment made and the series dates skipped as taken.
     */
    public record DeskBookedBody(
            UUID id,
            String reference,
            Instant start,
            Instant end,
            UUID groupId,
            UUID seriesId,
            List<BookedBody> appointments,
            List<Instant> skipped) {}

    public record BookedBody(UUID id, String reference, Instant start, Instant end) {}

    /** One person of a desk group booking. */
    public record DeskParty(UUID customerId, String name, String phone, List<Availability.Item> items) {}

    /** The front desk books a group (several people at once) or a series (one person every few weeks). */
    public DeskBookedBody deskBookMany(
            UUID orgId,
            UUID actorUserId,
            UUID locationId,
            List<DeskParty> parties,
            List<Instant> starts,
            Integer everyWeeks,
            String note,
            String source,
            boolean allowOverlap,
            boolean skipTaken,
            boolean notify,
            Instant now) {
        Bookings.DeskMany many = bookings.deskBookMany(
                orgId,
                actorUserId,
                locationId,
                parties.stream()
                        .map(p -> new Bookings.Party(new Bookings.Who(p.customerId(), p.name(), p.phone()), p.items()))
                        .toList(),
                starts,
                everyWeeks,
                note,
                source,
                allowOverlap,
                skipTaken,
                notify,
                now);
        Bookings.DeskBooked first = many.booked().getFirst();
        return new DeskBookedBody(
                first.appointmentId(),
                first.reference(),
                first.plan().start(),
                first.plan().end(),
                many.groupId(),
                many.seriesId(),
                many.booked().stream()
                        .map(b -> new BookedBody(
                                b.appointmentId(),
                                b.reference(),
                                b.plan().start(),
                                b.plan().end()))
                        .toList(),
                many.skipped());
    }

    /** The appointments a desk cancellation would touch. */
    public List<UUID> deskCancelTargets(UUID appointmentId, String scope) {
        return bookings.deskCancelTargets(appointmentId, Bookings.CancelScope.valueOf(scope));
    }

    /** The front desk cancels one visit, the rest of a series, or a whole group; returns the cancelled ids. */
    public List<UUID> deskCancel(
            UUID orgId, UUID actorUserId, UUID appointmentId, String scope, String reason, boolean notify) {
        return bookings.deskCancel(
                orgId, actorUserId, appointmentId, Bookings.CancelScope.valueOf(scope), reason, notify);
    }

    /** The front desk books a walk-in or phone customer (see {@link Bookings#deskBook}). */
    public DeskBookedBody deskBook(
            UUID orgId,
            UUID actorUserId,
            UUID locationId,
            List<Availability.Item> items,
            Instant start,
            UUID customerId,
            String name,
            String phone,
            String note,
            String source,
            boolean allowOverlap,
            boolean notify,
            Instant now) {
        Bookings.DeskBooked booked = bookings.deskBook(
                orgId,
                actorUserId,
                locationId,
                items,
                start,
                new Bookings.Who(customerId, name, phone),
                note,
                source,
                allowOverlap,
                notify,
                now);
        return new DeskBookedBody(
                booked.appointmentId(),
                booked.reference(),
                booked.plan().start(),
                booked.plan().end(),
                null,
                null,
                List.of(new BookedBody(
                        booked.appointmentId(),
                        booked.reference(),
                        booked.plan().start(),
                        booked.plan().end())),
                List.of());
    }

    public void deskMove(
            UUID orgId,
            UUID actorUserId,
            UUID appointmentId,
            Instant start,
            UUID staffId,
            boolean allowOverlap,
            boolean notify,
            Instant now) {
        bookings.deskMove(orgId, actorUserId, appointmentId, start, staffId, allowOverlap, notify, now);
    }

    public UUID block(
            UUID orgId,
            UUID actorUserId,
            UUID locationId,
            UUID staffId,
            Instant start,
            Instant end,
            String reason,
            String title) {
        return bookings.block(orgId, actorUserId, locationId, staffId, start, end, reason, title);
    }

    public void unblock(UUID blockId) {
        bookings.unblock(blockId);
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
                new CancellationBody(
                        c.allowed(),
                        c.free(),
                        c.freeUntil(),
                        c.feeMinor(),
                        c.currency(),
                        c.refundMinor(),
                        c.keptMinor()),
                d.canReschedule(),
                paymentBody(d.payment()),
                d.refund() == null
                        ? null
                        : new RefundBody(
                                d.refund().amountMinor(),
                                d.refund().currency(),
                                d.refund().state()));
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
