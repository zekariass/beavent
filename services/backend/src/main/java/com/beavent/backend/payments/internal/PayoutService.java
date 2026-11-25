package com.beavent.backend.payments.internal;

import static com.beavent.db.ledger.Tables.ACCOUNTS;
import static com.beavent.db.ledger.Tables.BALANCES;
import static com.beavent.db.ledger.Tables.SCHEDULED_RELEASES;
import static com.beavent.db.payments.Tables.ORG_SETTINGS;
import static com.beavent.db.payments.Tables.PAYOUTS;
import static com.beavent.db.payments.Tables.PAYOUT_ACCOUNTS;
import static com.beavent.db.tenant.Tables.ORGANIZATIONS;
import static com.beavent.db.trust.Tables.RISK_PROFILES;

import com.beavent.backend.ledger.Ledger;
import com.beavent.backend.ledger.Ledger.Account;
import com.beavent.backend.ledger.Ledger.Code;
import com.beavent.backend.ledger.Ledger.Links;
import com.beavent.backend.ledger.Ledger.Posting;
import com.beavent.backend.ledger.Postings;
import com.beavent.backend.payments.PaymentFulfilment;
import com.beavent.backend.payments.Payouts;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.DomainEvent;
import com.beavent.backend.platform.Outbox;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.SystemDb;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Releases, payout runs and the finance desk (plan 3.5). Everything that moves money posts to the ledger in the same
 * transaction as the change to the payout or release, and every transition checks the state it starts from, so a
 * repeated or racing request cannot pay or release twice.
 */
@Component
class PayoutService implements Payouts {

    private static final Logger log = LoggerFactory.getLogger(PayoutService.class);
    /** A payout is ready for the bank batch in these states. */
    private static final Set<String> READY = Set.of("SCHEDULED", "APPROVED");

    private static final Set<String> OPEN = Set.of("SCHEDULED", "PENDING_APPROVAL", "APPROVED", "PROCESSING");

    private final DSLContext system;
    private final TransactionTemplate transactions;
    private final Ledger ledger;
    private final PayoutsProperties props;
    private final Captures captures;
    private final List<PaymentFulfilment> fulfilments;
    private final ActiveProvider provider;
    private final ProviderAccounts providerAccounts;
    private final SellerAccounts accounts;
    private final Outbox outbox;

    PayoutService(
            @SystemDb DSLContext system,
            @SystemDb TransactionTemplate transactions,
            Ledger ledger,
            PayoutsProperties props,
            Captures captures,
            List<PaymentFulfilment> fulfilments,
            ActiveProvider provider,
            ProviderAccounts providerAccounts,
            SellerAccounts accounts,
            Outbox outbox) {
        this.system = system;
        this.transactions = transactions;
        this.ledger = ledger;
        this.props = props;
        this.captures = captures;
        this.fulfilments = fulfilments;
        this.provider = provider;
        this.providerAccounts = providerAccounts;
        this.accounts = accounts;
        this.outbox = outbox;
    }

    // --- release ---------------------------------------------------------------------------------------------------

    @Override
    public int releaseDue(Instant now) {
        List<UUID> due = system.select(SCHEDULED_RELEASES.ID)
                .from(SCHEDULED_RELEASES)
                .where(SCHEDULED_RELEASES.STATUS.eq("SCHEDULED"))
                .and(SCHEDULED_RELEASES.RELEASE_AT.le(now.atOffset(ZoneOffset.UTC)))
                .orderBy(SCHEDULED_RELEASES.RELEASE_AT)
                .limit(props.batchSize())
                .fetch(SCHEDULED_RELEASES.ID);
        for (UUID id : due) {
            try {
                transactions.executeWithoutResult(status -> releaseOne(id, now));
            } catch (RuntimeException e) {
                log.warn("Release {} failed; it is tried again at the next run", id, e);
            }
        }
        return due.size();
    }

    private void releaseOne(UUID id, Instant now) {
        var r = system.selectFrom(SCHEDULED_RELEASES)
                .where(SCHEDULED_RELEASES.ID.eq(id))
                .forUpdate()
                .skipLocked()
                .fetchOne();
        if (r == null || !r.getStatus().equals("SCHEDULED")) return;
        PaymentFulfilment.Delivery delivery = null;
        if (r.getOrderId() != null) {
            for (PaymentFulfilment f : fulfilments) {
                delivery = f.delivery(r.getOrgId(), r.getOrderId(), now);
                if (delivery != null) break;
            }
        }
        PaymentFulfilment.DeliveryState state =
                delivery == null ? PaymentFulfilment.DeliveryState.DELIVERED : delivery.state();
        switch (state) {
            case DELIVERED -> {
                Postings.Planned p = Postings.released(r.getOrgId(), r.getAmountMinor());
                UUID tx = ledger.post(new Posting(
                        p.kind(),
                        r.getOrgId(),
                        "release:" + id,
                        "Available for payout",
                        new Links(null, null, null, r.getOrderId()),
                        r.getCurrencyCode().trim(),
                        p.lines()));
                system.update(SCHEDULED_RELEASES)
                        .set(SCHEDULED_RELEASES.STATUS, "RELEASED")
                        .set(SCHEDULED_RELEASES.TRANSACTION_ID, tx)
                        .where(SCHEDULED_RELEASES.ID.eq(id))
                        .execute();
            }
            case PENDING -> {
                // Rescheduled or not confirmed yet: look again once it has ended and the delay has passed.
                Instant next = delivery.endsAt().plus(captures.delayDays(r.getOrgId()), ChronoUnit.DAYS);
                if (!next.isAfter(now)) next = now.plus(1, ChronoUnit.HOURS);
                system.update(SCHEDULED_RELEASES)
                        .set(SCHEDULED_RELEASES.RELEASE_AT, next.atOffset(ZoneOffset.UTC))
                        .where(SCHEDULED_RELEASES.ID.eq(id))
                        .execute();
            }
            case VOID ->
                system.update(SCHEDULED_RELEASES)
                        .set(SCHEDULED_RELEASES.STATUS, "ON_HOLD")
                        .where(SCHEDULED_RELEASES.ID.eq(id))
                        .execute();
        }
    }

    // --- the payout run --------------------------------------------------------------------------------------------

    @Override
    public int createDue(Instant now) {
        record Candidate(UUID orgId, String currency) {}
        List<Candidate> candidates = system.select(ACCOUNTS.ORG_ID, ACCOUNTS.CURRENCY_CODE)
                .from(BALANCES)
                .join(ACCOUNTS)
                .on(ACCOUNTS.ID.eq(BALANCES.ACCOUNT_ID))
                .where(ACCOUNTS.CODE.eq(Code.SELLER_AVAILABLE.name()))
                .and(BALANCES.BALANCE_MINOR.lt(0L))
                .fetch(r -> new Candidate(
                        r.get(ACCOUNTS.ORG_ID), r.get(ACCOUNTS.CURRENCY_CODE).trim()));
        int created = 0;
        for (Candidate c : candidates) {
            try {
                Boolean made = transactions.execute(status -> createFor(c.orgId(), c.currency(), now));
                if (Boolean.TRUE.equals(made)) created++;
            } catch (RuntimeException e) {
                log.warn("Payout run for {} failed; it is tried again at the next run", c.orgId(), e);
            }
        }
        return created;
    }

    private boolean createFor(UUID orgId, String currency, Instant now) {
        // Lock the balance first: nothing else may take money from it while the payout is made.
        Long locked = system.select(BALANCES.BALANCE_MINOR)
                .from(BALANCES)
                .join(ACCOUNTS)
                .on(ACCOUNTS.ID.eq(BALANCES.ACCOUNT_ID))
                .where(ACCOUNTS.ORG_ID.eq(orgId))
                .and(ACCOUNTS.CODE.eq(Code.SELLER_AVAILABLE.name()))
                .and(ACCOUNTS.CURRENCY_CODE.eq(currency))
                .forUpdate()
                .of(BALANCES)
                .fetchOne(BALANCES.BALANCE_MINOR);
        long amount = locked == null ? 0 : -locked;
        if (amount <= 0) return false;

        var org = system.select(ORGANIZATIONS.VERIFICATION_STATUS, ORGANIZATIONS.STATUS)
                .from(ORGANIZATIONS)
                .where(ORGANIZATIONS.ID.eq(orgId))
                .fetchOne();
        if (org == null || !org.value1().equals("VERIFIED") || org.value2().equals("SUSPENDED")) return false;
        var risk = system.selectFrom(RISK_PROFILES)
                .where(RISK_PROFILES.ORG_ID.eq(orgId))
                .fetchOne();
        if (risk != null && risk.getTier().equals("BLOCKED")) return false;
        var account = system.selectFrom(PAYOUT_ACCOUNTS)
                .where(PAYOUT_ACCOUNTS.ORG_ID.eq(orgId))
                .and(PAYOUT_ACCOUNTS.STATUS.eq("ACTIVE"))
                .and(PAYOUT_ACCOUNTS.VERIFICATION_STATUS.eq("VERIFIED"))
                .and(PAYOUT_ACCOUNTS.IS_DEFAULT.isTrue())
                .and(PAYOUT_ACCOUNTS.CURRENCY_CODE.eq(currency))
                .fetchOne();
        // A new or changed account waits out its cooling-off before it is paid into (docs/04 §9).
        if (account == null || account.getPayoutsAllowedAfter().toInstant().isAfter(now)) return false;

        var settings = system.selectFrom(ORG_SETTINGS)
                .where(ORG_SETTINGS.ORG_ID.eq(orgId))
                .limit(1)
                .fetchOne();
        String schedule = settings == null ? "WEEKLY" : settings.getPayoutSchedule();
        int weekday = settings == null || settings.getPayoutWeekday() == null
                ? props.defaultWeekday()
                : settings.getPayoutWeekday();
        long minimum = settings == null ? 0 : settings.getMinPayoutMinor();
        LocalDate today = now.atZone(props.zone()).toLocalDate();
        boolean dueToday = switch (schedule) {
            case "DAILY" -> true;
            case "WEEKLY" -> today.getDayOfWeek().getValue() == weekday;
            case "MONTHLY" -> today.getDayOfMonth() == 1;
            default -> false;
        };
        if (!dueToday || amount < Math.max(minimum, 1)) return false;

        List<String> reasons = new ArrayList<>();
        if (!system.fetchExists(
                PAYOUTS,
                PAYOUTS.ORG_ID.eq(orgId).and(PAYOUTS.PURPOSE.eq("SETTLEMENT")).and(PAYOUTS.STATUS.eq("PAID")))) {
            reasons.add("FIRST_PAYOUT");
        }
        if (amount >= props.approvalAboveMinor()
                || (risk != null
                        && risk.getMaxSinglePayoutMinor() != null
                        && amount > risk.getMaxSinglePayoutMinor())) {
            reasons.add("LARGE_AMOUNT");
        }
        OffsetDateTime reviewed = account.getReviewedAt();
        if (reviewed != null && reviewed.toInstant().isAfter(now.minus(props.recentAccountDays(), ChronoUnit.DAYS))) {
            reasons.add("NEW_ACCOUNT");
        }
        if (risk != null && risk.getManualPayoutReview()) reasons.add("RISK_REVIEW");
        boolean needsApproval = !reasons.isEmpty();

        UUID providerAccount = providerAccounts.platformAccount(provider.code()).orElse(null);
        UUID id = system.insertInto(PAYOUTS)
                .set(PAYOUTS.ORG_ID, orgId)
                .set(PAYOUTS.RECIPIENT_KIND, "ORGANIZATION")
                .set(PAYOUTS.PAYOUT_ACCOUNT_ID, account.getId())
                .set(PAYOUTS.PROVIDER_ACCOUNT_ID, providerAccount)
                .set(PAYOUTS.PURPOSE, "SETTLEMENT")
                .set(PAYOUTS.AMOUNT_MINOR, amount)
                .set(PAYOUTS.CURRENCY_CODE, currency)
                .set(PAYOUTS.STATUS, needsApproval ? "PENDING_APPROVAL" : "SCHEDULED")
                .set(PAYOUTS.SCHEDULED_FOR, now.atOffset(ZoneOffset.UTC))
                .set(PAYOUTS.REQUIRES_APPROVAL, needsApproval)
                .set(PAYOUTS.APPROVAL_REASONS, reasons.toArray(String[]::new))
                .set(PAYOUTS.IDEMPOTENCY_KEY, "payout:" + orgId + ":" + currency + ":" + today)
                .onConflict(PAYOUTS.IDEMPOTENCY_KEY)
                .doNothing()
                .returning(PAYOUTS.ID)
                .fetchOptional(PAYOUTS.ID)
                .orElse(null);
        if (id == null) return false; // today's payout for this seller already exists
        Postings.Planned p = Postings.payoutInitiated(orgId, amount);
        ledger.post(new Posting(
                p.kind(),
                orgId,
                "payout-initiated:" + id,
                "Payout created",
                new Links(null, null, id, null),
                currency,
                p.lines()));
        return true;
    }

    // --- the seller's view -----------------------------------------------------------------------------------------

    @Override
    public Overview overview(UUID orgId) {
        String currency = "ETB";
        Ledger.SellerBalance balance = ledger.sellerBalance(orgId, currency);
        Long onTheWay = system.select(DSL.sum(PAYOUTS.AMOUNT_MINOR))
                .from(PAYOUTS)
                .where(PAYOUTS.ORG_ID.eq(orgId), PAYOUTS.PURPOSE.eq("SETTLEMENT"), PAYOUTS.STATUS.in(OPEN))
                .fetchOne(0, Long.class);
        Long paid = system.select(DSL.sum(PAYOUTS.AMOUNT_MINOR))
                .from(PAYOUTS)
                .where(PAYOUTS.ORG_ID.eq(orgId), PAYOUTS.PURPOSE.eq("SETTLEMENT"), PAYOUTS.STATUS.eq("PAID"))
                .fetchOne(0, Long.class);
        var settings = system.selectFrom(ORG_SETTINGS)
                .where(ORG_SETTINGS.ORG_ID.eq(orgId))
                .limit(1)
                .fetchOne();
        String schedule = settings == null ? "WEEKLY" : settings.getPayoutSchedule();
        Integer weekday = schedule.equals("WEEKLY")
                ? (settings == null || settings.getPayoutWeekday() == null
                        ? props.defaultWeekday()
                        : (int) settings.getPayoutWeekday())
                : null;
        return new Overview(
                currency,
                balance.pendingMinor() + balance.reserveMinor(),
                balance.availableMinor(),
                onTheWay == null ? 0 : onTheWay,
                paid == null ? 0 : paid,
                schedule,
                weekday,
                settings == null ? 0 : settings.getMinPayoutMinor());
    }

    @Override
    public List<PayoutRow> payouts(UUID orgId, int limit) {
        return system.select(
                        PAYOUTS.ID,
                        PAYOUTS.STATUS,
                        PAYOUTS.AMOUNT_MINOR,
                        PAYOUTS.CURRENCY_CODE,
                        PAYOUT_ACCOUNTS.INSTITUTION_NAME,
                        PAYOUT_ACCOUNTS.ACCOUNT_NUMBER_MASKED,
                        PAYOUTS.CREATED_AT,
                        PAYOUTS.PAID_AT,
                        PAYOUTS.FAILURE_MESSAGE)
                .from(PAYOUTS)
                .leftJoin(PAYOUT_ACCOUNTS)
                .on(PAYOUT_ACCOUNTS.ID.eq(PAYOUTS.PAYOUT_ACCOUNT_ID))
                .where(PAYOUTS.ORG_ID.eq(orgId), PAYOUTS.PURPOSE.eq("SETTLEMENT"))
                .orderBy(PAYOUTS.ID.desc())
                .limit(limit)
                .fetch(r -> new PayoutRow(
                        r.get(PAYOUTS.ID),
                        r.get(PAYOUTS.STATUS),
                        r.get(PAYOUTS.AMOUNT_MINOR),
                        r.get(PAYOUTS.CURRENCY_CODE).trim(),
                        r.get(PAYOUT_ACCOUNTS.INSTITUTION_NAME),
                        r.get(PAYOUT_ACCOUNTS.ACCOUNT_NUMBER_MASKED),
                        r.get(PAYOUTS.CREATED_AT).toInstant(),
                        r.get(PAYOUTS.PAID_AT) == null
                                ? null
                                : r.get(PAYOUTS.PAID_AT).toInstant(),
                        r.get(PAYOUTS.FAILURE_MESSAGE)));
    }

    @Override
    public List<Ledger.StatementLine> statement(UUID orgId, UUID before, int limit) {
        return ledger.statement(orgId, "ETB", before, limit);
    }

    // --- the finance desk ------------------------------------------------------------------------------------------

    @Override
    public void decide(UUID payoutId, UUID reviewer, boolean approve, String note) {
        transactions.executeWithoutResult(status -> {
            var p = lock(payoutId);
            if (!p.getStatus().equals("PENDING_APPROVAL")) {
                throw new ApiException(ProblemCode.CONFLICT, "This payout is not waiting for approval.");
            }
            if (approve) {
                system.update(PAYOUTS)
                        .set(PAYOUTS.STATUS, "APPROVED")
                        .set(PAYOUTS.APPROVED_BY, reviewer)
                        .set(PAYOUTS.APPROVED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                        .set(PAYOUTS.DECISION_NOTE, note)
                        .set(PAYOUTS.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                        .where(PAYOUTS.ID.eq(payoutId))
                        .execute();
                return;
            }
            system.update(PAYOUTS)
                    .set(PAYOUTS.STATUS, "CANCELLED")
                    .set(PAYOUTS.DECISION_NOTE, note)
                    .set(PAYOUTS.FAILURE_MESSAGE, note)
                    .set(PAYOUTS.SETTLED_BY, reviewer)
                    .set(PAYOUTS.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                    .where(PAYOUTS.ID.eq(payoutId))
                    .execute();
            giveBack(p);
            publish("payout.rejected", p, note);
        });
    }

    @Override
    public String exportBatch(UUID by, List<UUID> payoutIds) {
        return transactions.execute(status -> {
            var q = system.select(
                            PAYOUTS.ID,
                            PAYOUTS.AMOUNT_MINOR,
                            PAYOUTS.CURRENCY_CODE,
                            PAYOUTS.PAYOUT_ACCOUNT_ID,
                            ORGANIZATIONS.DISPLAY_NAME,
                            PAYOUT_ACCOUNTS.INSTITUTION_NAME,
                            PAYOUT_ACCOUNTS.ACCOUNT_NAME)
                    .from(PAYOUTS)
                    .join(ORGANIZATIONS)
                    .on(ORGANIZATIONS.ID.eq(PAYOUTS.ORG_ID))
                    .join(PAYOUT_ACCOUNTS)
                    .on(PAYOUT_ACCOUNTS.ID.eq(PAYOUTS.PAYOUT_ACCOUNT_ID))
                    .where(PAYOUTS.STATUS.in(READY), PAYOUTS.PURPOSE.eq("SETTLEMENT"))
                    .and(payoutIds == null ? DSL.noCondition() : PAYOUTS.ID.in(payoutIds))
                    .orderBy(PAYOUTS.ID)
                    .forUpdate()
                    .of(PAYOUTS)
                    .fetch();
            StringBuilder csv =
                    new StringBuilder("reference,business,bank,account_holder,account_number,amount,currency\n");
            for (var r : q) {
                String currency = r.get(PAYOUTS.CURRENCY_CODE).trim();
                int digits = Currency.getInstance(currency).getDefaultFractionDigits();
                String amount =
                        BigDecimal.valueOf(r.get(PAYOUTS.AMOUNT_MINOR), digits).toPlainString();
                csv.append(cell(r.get(PAYOUTS.ID).toString()))
                        .append(',')
                        .append(cell(r.get(ORGANIZATIONS.DISPLAY_NAME)))
                        .append(',')
                        .append(cell(r.get(PAYOUT_ACCOUNTS.INSTITUTION_NAME)))
                        .append(',')
                        .append(cell(r.get(PAYOUT_ACCOUNTS.ACCOUNT_NAME)))
                        .append(',')
                        .append(cell(accounts.accountNumber(r.get(PAYOUTS.PAYOUT_ACCOUNT_ID))))
                        .append(',')
                        .append(amount)
                        .append(',')
                        .append(currency)
                        .append('\n');
                system.update(PAYOUTS)
                        .set(PAYOUTS.STATUS, "PROCESSING")
                        .set(PAYOUTS.EXPORTED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                        .set(PAYOUTS.EXPORTED_BY, by)
                        .set(PAYOUTS.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                        .where(PAYOUTS.ID.eq(r.get(PAYOUTS.ID)))
                        .execute();
            }
            return csv.toString();
        });
    }

    /** A spreadsheet cell: quoted, and never read as a formula (names are typed by sellers). */
    static String cell(String value) {
        String v = value == null ? "" : value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    @Override
    public void settle(UUID payoutId, UUID by, Settlement outcome, String reference, String reason) {
        transactions.executeWithoutResult(status -> {
            var p = lock(payoutId);
            Account clearing = Account.clearing(p.getProviderAccountId());
            String currency = p.getCurrencyCode().trim();
            OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
            switch (outcome) {
                case PAID -> {
                    if (!Set.of("SCHEDULED", "APPROVED", "PROCESSING").contains(p.getStatus())) {
                        throw new ApiException(ProblemCode.CONFLICT, "This payout cannot be marked paid now.");
                    }
                    system.update(PAYOUTS)
                            .set(PAYOUTS.STATUS, "PAID")
                            .set(PAYOUTS.PAID_AT, at)
                            .set(PAYOUTS.PROVIDER_REFERENCE, reference)
                            .set(PAYOUTS.SETTLED_BY, by)
                            .set(PAYOUTS.UPDATED_AT, at)
                            .where(PAYOUTS.ID.eq(payoutId))
                            .execute();
                    Postings.Planned posting = Postings.payoutSettled(clearing, p.getAmountMinor());
                    post(p, posting, "Payout paid");
                    publish("payout.paid", p, reference);
                }
                case FAILED -> {
                    if (!Set.of("SCHEDULED", "APPROVED", "PROCESSING").contains(p.getStatus())) {
                        throw new ApiException(ProblemCode.CONFLICT, "This payout cannot be marked failed now.");
                    }
                    system.update(PAYOUTS)
                            .set(PAYOUTS.STATUS, "FAILED")
                            .set(PAYOUTS.FAILURE_MESSAGE, reason)
                            .set(PAYOUTS.SETTLED_BY, by)
                            .set(PAYOUTS.UPDATED_AT, at)
                            .where(PAYOUTS.ID.eq(payoutId))
                            .execute();
                    giveBack(p);
                    publish("payout.failed", p, reason);
                }
                case RETURNED -> {
                    if (!p.getStatus().equals("PAID")) {
                        throw new ApiException(ProblemCode.CONFLICT, "Only a paid payout can be returned.");
                    }
                    system.update(PAYOUTS)
                            .set(PAYOUTS.STATUS, "RETURNED")
                            .set(PAYOUTS.FAILURE_MESSAGE, reason)
                            .set(PAYOUTS.SETTLED_BY, by)
                            .set(PAYOUTS.UPDATED_AT, at)
                            .where(PAYOUTS.ID.eq(payoutId))
                            .execute();
                    Postings.Planned posting = Postings.payoutReturned(clearing, p.getOrgId(), p.getAmountMinor());
                    post(p, posting, "Payout returned by the bank");
                    publish("payout.failed", p, reason);
                }
            }
        });
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    private com.beavent.db.payments.tables.records.PayoutsRecord lock(UUID payoutId) {
        var p = system.selectFrom(PAYOUTS)
                .where(PAYOUTS.ID.eq(payoutId), PAYOUTS.PURPOSE.eq("SETTLEMENT"))
                .forUpdate()
                .fetchOne();
        if (p == null) throw new ApiException(ProblemCode.NOT_FOUND);
        return p;
    }

    /** The payout did not happen before the bank: its money is available to the seller again. */
    private void giveBack(com.beavent.db.payments.tables.records.PayoutsRecord p) {
        post(p, Postings.payoutFailed(p.getOrgId(), p.getAmountMinor()), "Payout cancelled");
    }

    private void post(com.beavent.db.payments.tables.records.PayoutsRecord p, Postings.Planned planned, String text) {
        ledger.post(new Posting(
                planned.kind(),
                p.getOrgId(),
                planned.suffix() + ":" + p.getId(),
                text,
                new Links(null, null, p.getId(), null),
                p.getCurrencyCode().trim(),
                planned.lines()));
    }

    private void publish(String type, com.beavent.db.payments.tables.records.PayoutsRecord p, String note) {
        var account = system.select(PAYOUT_ACCOUNTS.INSTITUTION_NAME, PAYOUT_ACCOUNTS.ACCOUNT_NUMBER_MASKED)
                .from(PAYOUT_ACCOUNTS)
                .where(PAYOUT_ACCOUNTS.ID.eq(p.getPayoutAccountId()))
                .fetchOne();
        outbox.publish(new DomainEvent(
                "payments.payout",
                p.getId(),
                type,
                1,
                p.getOrgId(),
                Map.of(
                        "payoutId",
                        p.getId().toString(),
                        "amountMinor",
                        p.getAmountMinor(),
                        "currency",
                        p.getCurrencyCode().trim(),
                        "institutionName",
                        account == null ? "" : account.value1(),
                        "last4",
                        account == null ? "" : account.value2(),
                        "note",
                        note == null ? "" : note)));
    }
}
