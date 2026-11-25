package com.beavent.backend.payments.internal;

import static com.beavent.db.ledger.Tables.SCHEDULED_RELEASES;
import static com.beavent.db.payments.Tables.PROVIDER_ACCOUNTS;
import static com.beavent.db.trust.Tables.RISK_PROFILES;

import com.beavent.backend.ledger.Ledger;
import com.beavent.backend.ledger.Ledger.Account;
import com.beavent.backend.ledger.Ledger.Links;
import com.beavent.backend.ledger.Ledger.Posting;
import com.beavent.backend.ledger.Postings;
import com.beavent.backend.payments.PaymentFulfilment;
import com.beavent.backend.platform.SystemDb;
import com.beavent.db.payments.tables.records.PaymentAttemptsRecord;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/**
 * Puts a captured payment into the ledger (docs/04 §10), in the payment's own transaction: the money is at the
 * provider, owed to the seller less the platform's fees (Beavent collects), or only the platform's share has arrived
 * (split at source). Money that is not the seller's to keep (a duplicate, a late payment for a time that is gone) is
 * owed back to the payer. The seller's share is released for payout once the service is delivered.
 */
@Component
class Captures {

    private final DSLContext system;
    private final Ledger ledger;
    private final ActiveProvider providers;
    private final PayoutsProperties props;
    private final List<PaymentFulfilment> fulfilments;

    Captures(
            @SystemDb DSLContext system,
            Ledger ledger,
            ActiveProvider providers,
            PayoutsProperties props,
            List<PaymentFulfilment> fulfilments) {
        this.system = system;
        this.ledger = ledger;
        this.providers = providers;
        this.props = props;
        this.fulfilments = fulfilments;
    }

    /** A payment for an order that was confirmed. */
    void confirmed(PaymentAttemptsRecord a, UUID orderId, Instant now) {
        boolean split = splitAtSource(a);
        PlatformShare share =
                orderId == null ? PlatformShare.NONE : PlatformShare.of(system, orderId, a.getAmountMinor());
        String currency = a.getCurrencyCode().trim();
        Account clearing = Account.clearing(a.getProviderAccountId());
        for (Postings.Planned p : Postings.captured(
                split, a.getOrgId(), clearing, a.getAmountMinor(), share.feeMinor(), share.vatMinor())) {
            ledger.post(new Posting(
                    p.kind(),
                    a.getOrgId(),
                    p.suffix() + ":" + a.getId(),
                    p.suffix().equals("capture") ? "Payment received" : "Beavent fees",
                    new Links(a.getId(), null, null, orderId),
                    currency,
                    p.lines()));
        }
        long net = a.getAmountMinor() - share.totalMinor();
        if (!split && net > 0) {
            scheduleRelease(a, orderId, net, currency, now);
        }
    }

    /** A payment that goes back to the payer: held as a debt, not as the seller's money. */
    void receivedForRefund(PaymentAttemptsRecord a, UUID orderId) {
        Postings.Planned p = Postings.receivedForRefund(Account.clearing(a.getProviderAccountId()), a.getAmountMinor());
        ledger.post(new Posting(
                p.kind(),
                a.getOrgId(),
                p.suffix() + ":" + a.getId(),
                "Payment to be refunded",
                new Links(a.getId(), null, null, orderId),
                a.getCurrencyCode().trim(),
                p.lines()));
    }

    private boolean splitAtSource(PaymentAttemptsRecord a) {
        String code = system.select(PROVIDER_ACCOUNTS.PROVIDER_CODE)
                .from(PROVIDER_ACCOUNTS)
                .where(PROVIDER_ACCOUNTS.ID.eq(a.getProviderAccountId()))
                .fetchOne(PROVIDER_ACCOUNTS.PROVIDER_CODE);
        return code != null
                && providers
                        .byCode(code)
                        .map(adapter -> adapter.collectionMode().equals("PROVIDER_SPLIT"))
                        .orElse(false);
    }

    /** The seller's money is released a few days after the service ends, so a refund can still come out of it. */
    private void scheduleRelease(PaymentAttemptsRecord a, UUID orderId, long net, String currency, Instant now) {
        Instant ends = now;
        if (orderId != null) {
            for (PaymentFulfilment f : fulfilments) {
                PaymentFulfilment.Delivery d = f.delivery(a.getOrgId(), orderId, now);
                if (d != null) {
                    ends = d.endsAt().isAfter(now) ? d.endsAt() : now;
                    break;
                }
            }
        }
        system.insertInto(SCHEDULED_RELEASES)
                .set(SCHEDULED_RELEASES.ORG_ID, a.getOrgId())
                .set(SCHEDULED_RELEASES.ORDER_ID, orderId)
                .set(SCHEDULED_RELEASES.AMOUNT_MINOR, net)
                .set(SCHEDULED_RELEASES.CURRENCY_CODE, currency)
                .set(
                        SCHEDULED_RELEASES.RELEASE_AT,
                        ends.plus(delayDays(a.getOrgId()), ChronoUnit.DAYS).atOffset(ZoneOffset.UTC))
                .execute();
    }

    int delayDays(UUID orgId) {
        Short days = system.select(RISK_PROFILES.PAYOUT_DELAY_DAYS)
                .from(RISK_PROFILES)
                .where(RISK_PROFILES.ORG_ID.eq(orgId))
                .fetchOne(RISK_PROFILES.PAYOUT_DELAY_DAYS);
        return days == null ? props.defaultDelayDays() : days;
    }
}
