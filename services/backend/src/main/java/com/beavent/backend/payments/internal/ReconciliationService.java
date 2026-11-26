package com.beavent.backend.payments.internal;

import static com.beavent.db.payments.Tables.PAYMENT_ATTEMPTS;
import static com.beavent.db.payments.Tables.PAYOUTS;
import static com.beavent.db.payments.Tables.PROVIDER_ACCOUNTS;
import static com.beavent.db.payments.Tables.PROVIDER_CAPABILITIES;
import static com.beavent.db.payments.Tables.REFUNDS;
import static com.beavent.db.payments.Tables.STATEMENT_LINES;

import com.beavent.backend.ledger.Ledger;
import com.beavent.backend.ledger.Ledger.Account;
import com.beavent.backend.ledger.Ledger.Links;
import com.beavent.backend.ledger.Ledger.Posting;
import com.beavent.backend.ledger.Postings;
import com.beavent.backend.payments.Reconciliation;
import com.beavent.backend.payments.internal.provider.AttemptStatus;
import com.beavent.backend.payments.internal.provider.StatusResult;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.SystemDb;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Statement import, matching, the exceptions queue and certification (plan 3.9). Lines are matched on the reference the
 * provider echoes (our attempt's id, or the provider's own reference for it) and the amount, never on the amount
 * alone. A payment the provider says it took that we had given up on goes through the same late-payment path as a
 * notification would; one we confirmed that the statement does not show is an exception, not a correction.
 */
@Component
class ReconciliationService implements Reconciliation {

    private static final String MARKET = "ET";
    private static final ZoneId ADDIS = ZoneId.of("Africa/Addis_Ababa");
    private static final int MAX_LINES = 5000;
    private static final int MAX_DAYS = 93;
    private static final Set<String> TYPES =
            Set.of("COLLECTION", "REFUND", "FEE", "PAYOUT", "REVERSAL", "CHARGEBACK", "ADJUSTMENT", "SETTLEMENT");
    private static final Set<String> OPEN_LINES = Set.of("UNMATCHED", "AMOUNT_MISMATCH", "MISSING_INTERNALLY");
    /** A payment in one of these states, paid according to the provider, is taken as paid late. */
    private static final Set<String> COULD_BE_PAID =
            Set.of("UNKNOWN", "AWAITING_CUSTOMER", "PROCESSING", "EXPIRED", "CANCELLED", "CREATED");

    private final DSLContext system;
    private final TransactionTemplate transactions;
    private final Ledger ledger;
    private final PaymentProcessor processor;
    private final ActiveProvider provider;
    private final ProviderAccounts providerAccounts;

    ReconciliationService(
            @SystemDb DSLContext system,
            @SystemDb TransactionTemplate transactions,
            Ledger ledger,
            PaymentProcessor processor,
            ActiveProvider provider,
            ProviderAccounts providerAccounts) {
        this.system = system;
        this.transactions = transactions;
        this.ledger = ledger;
        this.processor = processor;
        this.provider = provider;
        this.providerAccounts = providerAccounts;
    }

    // --- accounts ----------------------------------------------------------------------------------------------------

    @Override
    public List<AccountRow> accounts() {
        return system.selectFrom(PROVIDER_ACCOUNTS)
                .where(PROVIDER_ACCOUNTS.OWNER.eq("PLATFORM"))
                .and(PROVIDER_ACCOUNTS.PROVIDER_CODE.notIn("CASH", "INTERNAL", "MANUAL_BANK"))
                .orderBy(PROVIDER_ACCOUNTS.PROVIDER_CODE, PROVIDER_ACCOUNTS.ENVIRONMENT)
                .fetch(r -> new AccountRow(
                        r.getId(),
                        r.getProviderCode(),
                        r.getDisplayName(),
                        r.getEnvironment(),
                        r.getSettlementCurrency().trim()));
    }

    // --- import ------------------------------------------------------------------------------------------------------

    private record Parsed(int lineNo, String reference, String type, long amountMinor, long feeMinor, Instant at) {}

    @Override
    public StatementRow importStatement(Upload upload, UUID byUserId, Instant now) {
        if (upload.providerAccountId() == null
                || upload.from() == null
                || upload.to() == null
                || upload.to().isBefore(upload.from())) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Say which days the statement covers.");
        }
        if (upload.from().plusDays(MAX_DAYS).isBefore(upload.to())) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "A statement covers at most three months.");
        }
        List<Parsed> lines = parse(upload.csv());
        UUID statementId = transactions.execute(status -> {
            var account = system.selectFrom(PROVIDER_ACCOUNTS)
                    .where(PROVIDER_ACCOUNTS.ID.eq(upload.providerAccountId()))
                    .and(PROVIDER_ACCOUNTS.OWNER.eq("PLATFORM"))
                    .fetchOne();
            if (account == null) throw new ApiException(ProblemCode.NOT_FOUND, "There is no such provider account.");
            boolean overlaps = system.fetchOne(
                            "select exists (select 1 from payments.provider_statements where provider_account_id = ?"
                                    + " and period && daterange(?, ?, '[]'))",
                            account.getId(),
                            upload.from(),
                            upload.to())
                    .get(0, Boolean.class);
            if (overlaps) {
                throw new ApiException(
                        ProblemCode.CONFLICT, "A statement for these days (or some of them) was imported already.");
            }
            String currency = account.getSettlementCurrency().trim();
            UUID id = system.fetchOne(
                            "insert into payments.provider_statements (provider_account_id, period, currency_code, source,"
                                    + " opening_balance_minor, closing_balance_minor, imported_by, status)"
                                    + " values (?, daterange(?, ?, '[]'), ?, 'UPLOAD', ?, ?, ?, 'RECONCILING') returning id",
                            account.getId(),
                            upload.from(),
                            upload.to(),
                            currency,
                            upload.openingBalanceMinor(),
                            upload.closingBalanceMinor(),
                            byUserId)
                    .get(0, UUID.class);
            for (Parsed p : lines) {
                system.insertInto(STATEMENT_LINES)
                        .set(STATEMENT_LINES.STATEMENT_ID, id)
                        .set(STATEMENT_LINES.LINE_NO, p.lineNo())
                        .set(STATEMENT_LINES.PROVIDER_REFERENCE, p.reference())
                        .set(STATEMENT_LINES.LINE_TYPE, p.type())
                        .set(STATEMENT_LINES.AMOUNT_MINOR, p.amountMinor())
                        .set(STATEMENT_LINES.FEE_MINOR, p.feeMinor())
                        .set(STATEMENT_LINES.OCCURRED_AT, p.at().atOffset(ZoneOffset.UTC))
                        .execute();
            }
            for (var line : system.selectFrom(STATEMENT_LINES)
                    .where(STATEMENT_LINES.STATEMENT_ID.eq(id))
                    .orderBy(STATEMENT_LINES.LINE_NO)
                    .fetch()) {
                match(account.getId(), currency, line, now);
            }
            // A payment still "unknown" that the statement of its day does not show was never paid.
            for (UUID unknown : system.select(PAYMENT_ATTEMPTS.ID)
                    .from(PAYMENT_ATTEMPTS)
                    .where(PAYMENT_ATTEMPTS.PROVIDER_ACCOUNT_ID.eq(account.getId()))
                    .and(PAYMENT_ATTEMPTS.STATUS.eq("UNKNOWN"))
                    .and(DSL.condition(
                            "(initiated_at at time zone 'Africa/Addis_Ababa')::date <= ?::date", upload.to()))
                    .fetch(PAYMENT_ATTEMPTS.ID)) {
                processor.fail(unknown, "NOT_ON_STATEMENT", "The provider's statement does not show this payment", now);
            }
            balance(id, account.getId(), currency, upload, now);
            refresh(id, now);
            return id;
        });
        return row(statementId);
    }

    /** One line against our records. */
    private void match(
            UUID accountId,
            String currency,
            com.beavent.db.payments.tables.records.StatementLinesRecord line,
            Instant now) {
        String type = line.getLineType();
        long amount = Math.abs(line.getAmountMinor());
        String ref = line.getProviderReference();
        switch (type) {
            case "COLLECTION" -> {
                var attempt = attemptOf(accountId, ref);
                if (attempt == null) {
                    if (amount > 0) {
                        UUID tx = post(
                                Postings.unexplainedReceipt(Account.clearing(accountId), amount),
                                line.getId(),
                                null,
                                currency,
                                "Money on the provider's statement with no payment of ours");
                        set(
                                line,
                                "MISSING_INTERNALLY",
                                "The provider took this and we have no such payment.",
                                null,
                                null,
                                null,
                                tx);
                    } else set(line, "MISSING_INTERNALLY", "No such payment.", null, null, null, null);
                    return;
                }
                if (system.fetchExists(STATEMENT_LINES, STATEMENT_LINES.MATCHED_ATTEMPT_ID.eq(attempt.getId()))) {
                    set(
                            line,
                            "UNMATCHED",
                            "This payment is on a statement already.",
                            attempt.getId(),
                            null,
                            null,
                            null);
                    return;
                }
                boolean same = attempt.getAmountMinor() == amount
                        && attempt.getCurrencyCode().trim().equals(currency);
                if (!same) {
                    set(
                            line,
                            "AMOUNT_MISMATCH",
                            "We asked for " + birr(attempt.getAmountMinor()) + " and the provider took " + birr(amount)
                                    + ".",
                            attempt.getId(),
                            null,
                            null,
                            null);
                    return;
                }
                String state = attempt.getStatus();
                String note = null;
                if (!state.equals("SUCCEEDED") && !state.equals("SUCCEEDED_DUPLICATE")) {
                    if (!COULD_BE_PAID.contains(state)) {
                        set(
                                line,
                                "AMOUNT_MISMATCH",
                                "We recorded this payment as " + state + ".",
                                attempt.getId(),
                                null,
                                null,
                                null);
                        return;
                    }
                    // The provider has it as paid and we never heard: the same path as a late notification.
                    processor.apply(
                            attempt.getId(),
                            new StatusResult(
                                    AttemptStatus.SUCCEEDED,
                                    amount,
                                    currency,
                                    attempt.getId().toString(),
                                    ref,
                                    null,
                                    "STATEMENT"),
                            now);
                    note = "Paid according to the statement; it was " + state + " with us.";
                }
                UUID tx = null;
                long fee = Math.max(0, line.getFeeMinor());
                if (fee > 0) {
                    tx = post(
                            Postings.providerFee(Account.clearing(accountId), fee),
                            line.getId(),
                            attempt.getId(),
                            currency,
                            "Fee kept by the provider");
                    system.update(PAYMENT_ATTEMPTS)
                            .set(PAYMENT_ATTEMPTS.PROVIDER_FEE_MINOR, fee)
                            .where(PAYMENT_ATTEMPTS.ID.eq(attempt.getId()))
                            .execute();
                }
                set(line, "MATCHED", note, attempt.getId(), null, null, tx);
            }
            case "REFUND" -> {
                var refund = system.selectFrom(REFUNDS)
                        .where(byReference(REFUNDS.PROVIDER_REFERENCE, REFUNDS.ID, ref))
                        .fetchOne();
                if (refund == null) {
                    set(line, "MISSING_INTERNALLY", "No refund of ours has this reference.", null, null, null, null);
                } else if (refund.getAmountMinor() != amount) {
                    set(
                            line,
                            "AMOUNT_MISMATCH",
                            "We refunded " + birr(refund.getAmountMinor()) + ".",
                            null,
                            refund.getId(),
                            null,
                            null);
                } else {
                    set(line, "MATCHED", null, null, refund.getId(), null, null);
                }
            }
            case "PAYOUT" -> {
                var payout = system.selectFrom(PAYOUTS)
                        .where(byReference(PAYOUTS.PROVIDER_REFERENCE, PAYOUTS.ID, ref))
                        .fetchOne();
                if (payout == null) {
                    set(line, "MISSING_INTERNALLY", "No payout of ours has this reference.", null, null, null, null);
                } else if (payout.getAmountMinor() != amount) {
                    set(
                            line,
                            "AMOUNT_MISMATCH",
                            "We paid out " + birr(payout.getAmountMinor()) + ".",
                            null,
                            null,
                            payout.getId(),
                            null);
                } else {
                    set(line, "MATCHED", null, null, null, payout.getId(), null);
                }
            }
            case "FEE" -> {
                if (amount > 0) {
                    UUID tx = post(
                            Postings.providerFee(Account.clearing(accountId), amount),
                            line.getId(),
                            null,
                            currency,
                            "Fee charged by the provider");
                    set(line, "MATCHED", "Provider fee.", null, null, null, tx);
                } else set(line, "IGNORED", "Nothing to post.", null, null, null, null);
            }
            case "SETTLEMENT" -> set(line, "IGNORED", "Settled to the bank.", null, null, null, null);
            default -> set(line, "UNMATCHED", "Finance has to say what this is.", null, null, null, null);
        }
    }

    private static String birr(long minor) {
        return String.format("%d.%02d", minor / 100, Math.abs(minor % 100));
    }

    private static Condition byReference(
            org.jooq.Field<String> providerReference, org.jooq.Field<UUID> id, String reference) {
        if (reference == null) return DSL.falseCondition();
        Condition c = providerReference.eq(reference);
        try {
            return c.or(id.eq(UUID.fromString(reference)));
        } catch (IllegalArgumentException e) {
            return c;
        }
    }

    private com.beavent.db.payments.tables.records.PaymentAttemptsRecord attemptOf(UUID accountId, String ref) {
        if (ref == null) return null;
        return system.selectFrom(PAYMENT_ATTEMPTS)
                .where(PAYMENT_ATTEMPTS.PROVIDER_ACCOUNT_ID.eq(accountId))
                .and(byReference(PAYMENT_ATTEMPTS.PROVIDER_REFERENCE, PAYMENT_ATTEMPTS.ID, ref))
                .fetchOne();
    }

    private void set(
            com.beavent.db.payments.tables.records.StatementLinesRecord line,
            String status,
            String note,
            UUID attemptId,
            UUID refundId,
            UUID payoutId,
            UUID tx) {
        system.update(STATEMENT_LINES)
                .set(STATEMENT_LINES.MATCH_STATUS, status)
                .set(STATEMENT_LINES.MATCH_NOTE, note)
                .set(STATEMENT_LINES.MATCHED_ATTEMPT_ID, attemptId)
                .set(STATEMENT_LINES.MATCHED_REFUND_ID, refundId)
                .set(STATEMENT_LINES.MATCHED_PAYOUT_ID, payoutId)
                .set(STATEMENT_LINES.LEDGER_TRANSACTION_ID, tx)
                .where(STATEMENT_LINES.ID.eq(line.getId()))
                .execute();
    }

    private UUID post(Postings.Planned planned, UUID lineId, UUID attemptId, String currency, String description) {
        return ledger.post(new Posting(
                planned.kind(),
                null,
                planned.suffix() + ":" + lineId,
                description,
                new Links(attemptId, null, null, null),
                currency,
                planned.lines()));
    }

    /** What the ledger says the account holds, as of the end of the period (or now, if the period is not over). */
    private void balance(UUID statementId, UUID accountId, String currency, Upload upload, Instant now) {
        Instant end = upload.to().plusDays(1).atStartOfDay(ADDIS).toInstant();
        Instant asOf = end.isBefore(now) ? end : now;
        long ledgerBalance = system.fetchOne(
                        "select coalesce(sum(e.amount_minor), 0) from ledger.entries e"
                                + " join ledger.accounts a on a.id = e.account_id"
                                + " join ledger.transactions t on t.id = e.transaction_id"
                                + " where a.provider_account_id = ? and a.code = 'PROVIDER_CLEARING'"
                                + " and a.currency_code = ? and t.occurred_at < ?::timestamptz",
                        accountId,
                        currency,
                        asOf.toString())
                .get(0, BigDecimal.class)
                .longValueExact();
        system.execute(
                "update payments.provider_statements set ledger_balance_minor = ?, difference_minor = ? where id = ?",
                ledgerBalance,
                upload.closingBalanceMinor() == null ? null : upload.closingBalanceMinor() - ledgerBalance,
                statementId);
    }

    /** Status from what is left to explain. */
    private void refresh(UUID statementId, Instant now) {
        var s = system.fetchOne(
                "select difference_minor, balance_note from payments.provider_statements where id = ?", statementId);
        long open = system.fetchCount(
                STATEMENT_LINES,
                STATEMENT_LINES.STATEMENT_ID.eq(statementId).and(STATEMENT_LINES.MATCH_STATUS.in(OPEN_LINES)));
        long missing = missingAttempts(statementId).size();
        Long difference = s.get(0, Long.class);
        boolean balanced = difference == null || difference == 0 || s.get(1, String.class) != null;
        boolean ok = open == 0 && missing == 0 && balanced;
        system.execute(
                "update payments.provider_statements set status = ?, reconciled_at = ?::timestamptz where id = ?",
                ok ? "RECONCILED" : "EXCEPTIONS",
                ok ? now.toString() : null,
                statementId);
    }

    /** Payments we confirmed on the statement's days that the statement does not show. */
    private List<Record> missingAttempts(UUID statementId) {
        return system.fetch(
                "select t.id, t.provider_reference, t.amount_minor, t.currency_code, t.completed_at, s.id as statement_id"
                        + " from payments.payment_attempts t"
                        + " join payments.provider_statements s on s.provider_account_id = t.provider_account_id"
                        + " where (?::uuid is null or s.id = ?::uuid) and t.status = 'SUCCEEDED'"
                        + " and (t.completed_at at time zone 'Africa/Addis_Ababa')::date <@ s.period"
                        + " and not exists (select 1 from payments.statement_lines l where l.matched_attempt_id = t.id)"
                        + " order by t.completed_at",
                statementId,
                statementId);
    }

    // --- reading -----------------------------------------------------------------------------------------------------

    private static final String STATEMENT_SELECT = "select s.id, s.provider_account_id, a.provider_code,"
            + " lower(s.period) as from_day, upper(s.period) - 1 as to_day, s.currency_code, s.status,"
            + " s.closing_balance_minor, s.ledger_balance_minor, s.difference_minor, s.imported_at,"
            + " (select count(*) from payments.statement_lines l where l.statement_id = s.id) as lines,"
            + " (select count(*) from payments.statement_lines l where l.statement_id = s.id and l.match_status = 'MATCHED') as matched,"
            + " (select count(*) from payments.statement_lines l where l.statement_id = s.id"
            + "    and l.match_status in ('UNMATCHED','AMOUNT_MISMATCH','MISSING_INTERNALLY')) as open_lines"
            + " from payments.provider_statements s join payments.provider_accounts a on a.id = s.provider_account_id";

    private StatementRow row(UUID id) {
        return system.fetchOptional(STATEMENT_SELECT + " where s.id = ?", id)
                .map(this::statementRow)
                .orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "There is no such statement."));
    }

    private StatementRow statementRow(Record r) {
        UUID id = r.get("id", UUID.class);
        int missing = missingAttempts(id).size();
        return new StatementRow(
                id,
                r.get("provider_account_id", UUID.class),
                r.get("provider_code", String.class),
                r.get("from_day", LocalDate.class),
                r.get("to_day", LocalDate.class),
                r.get("currency_code", String.class).trim(),
                r.get("status", String.class),
                r.get("lines", Integer.class),
                r.get("matched", Integer.class),
                r.get("open_lines", Integer.class) + missing,
                r.get("closing_balance_minor", Long.class),
                r.get("ledger_balance_minor", Long.class),
                r.get("difference_minor", Long.class),
                r.get("imported_at", OffsetDateTime.class).toInstant());
    }

    @Override
    public List<StatementRow> statements(int limit) {
        return system.fetch(STATEMENT_SELECT + " order by s.period desc, s.imported_at desc limit ?", limit).stream()
                .map(this::statementRow)
                .toList();
    }

    @Override
    public List<ExceptionRow> exceptions(int limit) {
        List<ExceptionRow> out = new ArrayList<>();
        for (Record r : system.fetch(
                "select l.id, l.statement_id, l.matched_attempt_id, l.provider_reference, l.line_type, l.amount_minor,"
                        + " s.currency_code, l.occurred_at, l.match_note, l.match_status"
                        + " from payments.statement_lines l join payments.provider_statements s on s.id = l.statement_id"
                        + " where l.match_status in ('UNMATCHED','AMOUNT_MISMATCH','MISSING_INTERNALLY')"
                        + " order by l.occurred_at limit ?",
                limit)) {
            out.add(new ExceptionRow(
                    r.get("match_status", String.class),
                    r.get("statement_id", UUID.class),
                    r.get("id", UUID.class),
                    r.get("matched_attempt_id", UUID.class),
                    r.get("provider_reference", String.class),
                    r.get("line_type", String.class),
                    r.get("amount_minor", Long.class),
                    r.get("currency_code", String.class).trim(),
                    r.get("occurred_at", OffsetDateTime.class).toInstant(),
                    r.get("match_note", String.class)));
        }
        for (Record r : missingAttempts(null)) {
            out.add(new ExceptionRow(
                    "MISSING_ON_STATEMENT",
                    r.get("statement_id", UUID.class),
                    null,
                    r.get("id", UUID.class),
                    r.get("provider_reference", String.class),
                    "COLLECTION",
                    r.get("amount_minor", Long.class),
                    r.get("currency_code", String.class).trim(),
                    r.get("completed_at", OffsetDateTime.class).toInstant(),
                    "We confirmed this payment and the statement does not show it."));
        }
        for (Record r : system.fetch(
                "select s.id, s.difference_minor, s.currency_code, s.imported_at from payments.provider_statements s"
                        + " where s.difference_minor is not null and s.difference_minor <> 0 and s.balance_note is null")) {
            out.add(new ExceptionRow(
                    "BALANCE_DIFFERENCE",
                    r.get("id", UUID.class),
                    null,
                    null,
                    null,
                    "BALANCE",
                    r.get("difference_minor", Long.class),
                    r.get("currency_code", String.class).trim(),
                    r.get("imported_at", OffsetDateTime.class).toInstant(),
                    "The statement's closing balance is not what the ledger says."));
        }
        return out.stream().limit(limit).toList();
    }

    @Override
    public void resolve(UUID statementId, UUID lineId, UUID attemptId, UUID byUserId, String note, Instant now) {
        if (note == null || note.strip().length() < 3) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Say why it is fine.");
        }
        String why = note.strip();
        transactions.executeWithoutResult(status -> {
            if (lineId != null) {
                int n = system.update(STATEMENT_LINES)
                        .set(STATEMENT_LINES.MATCH_STATUS, "IGNORED")
                        .set(STATEMENT_LINES.RESOLUTION_NOTE, why)
                        .set(STATEMENT_LINES.RESOLVED_BY, byUserId)
                        .set(STATEMENT_LINES.RESOLVED_AT, now.atOffset(ZoneOffset.UTC))
                        .where(STATEMENT_LINES.ID.eq(lineId))
                        .and(STATEMENT_LINES.STATEMENT_ID.eq(statementId))
                        .and(STATEMENT_LINES.MATCH_STATUS.in(OPEN_LINES))
                        .execute();
                if (n == 0) throw new ApiException(ProblemCode.NOT_FOUND, "Nothing to explain there.");
            } else if (attemptId != null) {
                boolean missing =
                        missingAttempts(statementId).stream().anyMatch(r -> attemptId.equals(r.get("id", UUID.class)));
                if (!missing) throw new ApiException(ProblemCode.NOT_FOUND, "Nothing to explain there.");
                system.insertInto(STATEMENT_LINES)
                        .set(STATEMENT_LINES.STATEMENT_ID, statementId)
                        .set(STATEMENT_LINES.LINE_NO, 0)
                        .set(STATEMENT_LINES.PROVIDER_REFERENCE, attemptId.toString())
                        .set(STATEMENT_LINES.LINE_TYPE, "ADJUSTMENT")
                        .set(STATEMENT_LINES.AMOUNT_MINOR, 0L)
                        .set(STATEMENT_LINES.OCCURRED_AT, now.atOffset(ZoneOffset.UTC))
                        .set(STATEMENT_LINES.MATCH_STATUS, "IGNORED")
                        .set(STATEMENT_LINES.MATCHED_ATTEMPT_ID, attemptId)
                        .set(STATEMENT_LINES.RESOLUTION_NOTE, why)
                        .set(STATEMENT_LINES.RESOLVED_BY, byUserId)
                        .set(STATEMENT_LINES.RESOLVED_AT, now.atOffset(ZoneOffset.UTC))
                        .execute();
            } else {
                int n = system.execute(
                        "update payments.provider_statements set balance_note = ? where id = ?"
                                + " and difference_minor is not null and difference_minor <> 0 and balance_note is null",
                        why,
                        statementId);
                if (n == 0) throw new ApiException(ProblemCode.NOT_FOUND, "Nothing to explain there.");
            }
            refresh(statementId, now);
        });
    }

    // --- parsing -----------------------------------------------------------------------------------------------------

    private List<Parsed> parse(String csv) {
        if (csv == null || csv.isBlank()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The statement file is empty.");
        }
        List<List<String>> rows = new ArrayList<>();
        String[] physical = csv.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        char delimiter = physical[0].contains(";") && !physical[0].contains(",") ? ';' : ',';
        for (String line : physical) {
            if (!line.isBlank()) rows.add(split(line, delimiter));
        }
        List<String> header = rows.getFirst().stream()
                .map(h -> h.strip().toLowerCase(Locale.ROOT))
                .toList();
        int ref = header.indexOf("reference");
        int type = header.indexOf("type");
        int amount = header.indexOf("amount");
        int date = header.indexOf("date") >= 0 ? header.indexOf("date") : header.indexOf("occurred_at");
        int fee = header.indexOf("fee");
        if (ref < 0 || type < 0 || amount < 0 || date < 0) {
            throw new ApiException(
                    ProblemCode.VALIDATION_FAILED,
                    "The first line must name the columns reference, type, amount and date (and may name fee).");
        }
        if (rows.size() - 1 > MAX_LINES) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "A statement has at most " + MAX_LINES + " lines.");
        }
        List<Parsed> out = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> cells = rows.get(i);
            int lineNo = i + 1;
            String kind = cell(cells, type).toUpperCase(Locale.ROOT);
            if (!TYPES.contains(kind)) {
                throw new ApiException(
                        ProblemCode.VALIDATION_FAILED, "Line " + lineNo + ": unknown type '" + kind + "'.");
            }
            long amountMinor = money(cell(cells, amount), lineNo, "amount");
            long feeMinor =
                    fee < 0 || cell(cells, fee).isEmpty() ? 0 : Math.abs(money(cell(cells, fee), lineNo, "fee"));
            out.add(new Parsed(
                    lineNo,
                    emptyToNull(cell(cells, ref)),
                    kind,
                    amountMinor,
                    feeMinor,
                    when(cell(cells, date), lineNo)));
        }
        return out;
    }

    private static String cell(List<String> cells, int index) {
        return index < cells.size() ? cells.get(index).strip() : "";
    }

    private static String emptyToNull(String s) {
        return s.isEmpty() ? null : s;
    }

    private static List<String> split(String line, char delimiter) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else quoted = !quoted;
            } else if (c == delimiter && !quoted) {
                out.add(cur.toString());
                cur.setLength(0);
            } else cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }

    /** Major units as the file writes them ("1,800.00", "1800", "-45.5") to minor units (two decimals). */
    private static long money(String raw, int lineNo, String column) {
        try {
            String s = raw.replace(" ", "");
            s = s.contains(".") ? s.replace(",", "") : s.replace(',', '.');
            return new BigDecimal(s)
                    .movePointRight(2)
                    .setScale(0, java.math.RoundingMode.UNNECESSARY)
                    .longValueExact();
        } catch (RuntimeException e) {
            throw new ApiException(
                    ProblemCode.VALIDATION_FAILED, "Line " + lineNo + ": '" + raw + "' is not a valid " + column + ".");
        }
    }

    private static Instant when(String raw, int lineNo) {
        String s = raw.strip();
        try {
            if (s.length() == 10) return LocalDate.parse(s).atStartOfDay(ADDIS).toInstant();
            if (s.endsWith("Z") || s.matches(".*[+-]\\d\\d:\\d\\d$"))
                return OffsetDateTime.parse(s.replace(' ', 'T')).toInstant();
            return LocalDateTime.parse(s.replace(' ', 'T')).atZone(ADDIS).toInstant();
        } catch (RuntimeException e) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Line " + lineNo + ": '" + raw + "' is not a date.");
        }
    }

    // --- certification -----------------------------------------------------------------------------------------------

    @Override
    public Certification certification() {
        String code = provider.code();
        var capabilities = system.selectFrom(PROVIDER_CAPABILITIES)
                .where(PROVIDER_CAPABILITIES.PROVIDER_CODE.eq(code))
                .and(PROVIDER_CAPABILITIES.COUNTRY_CODE.eq(MARKET))
                .and(PROVIDER_CAPABILITIES.CURRENCY_CODE.eq("ETB"))
                .fetch();
        String status = capabilities.stream().anyMatch(c -> c.getStatus().equals("LIVE"))
                ? "LIVE"
                : capabilities.stream().anyMatch(c -> c.getStatus().equals("SUSPENDED")) ? "SUSPENDED" : "SANDBOX";
        Instant verified = capabilities.stream()
                .map(c -> c.getVerifiedAt())
                .filter(java.util.Objects::nonNull)
                .map(OffsetDateTime::toInstant)
                .max(Instant::compareTo)
                .orElse(null);
        long collections = system.fetchCount(system.selectOne()
                .from(PAYMENT_ATTEMPTS)
                .join(PROVIDER_ACCOUNTS)
                .on(PROVIDER_ACCOUNTS.ID.eq(PAYMENT_ATTEMPTS.PROVIDER_ACCOUNT_ID))
                .where(PROVIDER_ACCOUNTS.PROVIDER_CODE.eq(code))
                .and(PAYMENT_ATTEMPTS.STATUS.eq("SUCCEEDED")));
        long refunds = system.fetchCount(REFUNDS, REFUNDS.STATUS.eq("SUCCEEDED"));
        long payouts = system.fetchCount(PAYOUTS, PAYOUTS.STATUS.eq("PAID"));
        long reconciled = system.fetchOne(
                        "select count(*) from payments.provider_statements s join payments.provider_accounts a on a.id = s.provider_account_id"
                                + " where a.provider_code = ? and s.status = 'RECONCILED'"
                                + " and exists (select 1 from payments.statement_lines l where l.statement_id = s.id"
                                + "   and l.line_type = 'COLLECTION' and l.match_status = 'MATCHED')",
                        code)
                .get(0, Long.class);
        long open = exceptions(1).size();
        List<Check> checks = List.of(
                new Check(
                        "CAPABILITIES",
                        !capabilities.isEmpty(),
                        capabilities.size() + " ways of paying set up for Ethiopia"),
                new Check("COLLECTION", collections > 0, collections + " payments confirmed by lookup"),
                new Check("REFUND", refunds > 0, refunds + " refunds paid"),
                new Check("PAYOUT", payouts > 0, payouts + " payouts paid"),
                new Check("RECONCILED", reconciled > 0, reconciled + " statements reconciled with payments matched"),
                new Check(
                        "NO_EXCEPTIONS",
                        open == 0,
                        open == 0 ? "nothing left to explain" : "something is left to explain"));
        boolean ready = checks.stream().allMatch(Check::passed);
        return new Certification(code, status, verified, checks, ready);
    }

    @Override
    public void goLive(UUID byUserId, String evidence, Instant now) {
        if (evidence == null || evidence.strip().length() < 10) {
            throw new ApiException(
                    ProblemCode.VALIDATION_FAILED,
                    "Say what the live low-value payment, refund and payout were (references).");
        }
        Certification c = certification();
        if (!c.ready()) {
            throw new ApiException(ProblemCode.CONFLICT, "Not every check passes yet.");
        }
        transactions.executeWithoutResult(status -> system.update(PROVIDER_CAPABILITIES)
                .set(PROVIDER_CAPABILITIES.STATUS, "LIVE")
                .set(PROVIDER_CAPABILITIES.VERIFIED_AT, now.atOffset(ZoneOffset.UTC))
                .set(PROVIDER_CAPABILITIES.NOTES, evidence.strip())
                .where(PROVIDER_CAPABILITIES.PROVIDER_CODE.eq(c.provider()))
                .and(PROVIDER_CAPABILITIES.COUNTRY_CODE.eq(MARKET))
                .and(PROVIDER_CAPABILITIES.CURRENCY_CODE.eq("ETB"))
                .and(PROVIDER_CAPABILITIES.STATUS.in("SANDBOX", "SUSPENDED"))
                .execute());
        providerAccounts.register();
    }

    @Override
    public void suspend(UUID byUserId, String reason, Instant now) {
        if (reason == null || reason.strip().length() < 3) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Say why.");
        }
        transactions.executeWithoutResult(status -> system.update(PROVIDER_CAPABILITIES)
                .set(PROVIDER_CAPABILITIES.STATUS, "SUSPENDED")
                .set(PROVIDER_CAPABILITIES.NOTES, reason.strip())
                .where(PROVIDER_CAPABILITIES.PROVIDER_CODE.eq(provider.code()))
                .and(PROVIDER_CAPABILITIES.COUNTRY_CODE.eq(MARKET))
                .and(PROVIDER_CAPABILITIES.CURRENCY_CODE.eq("ETB"))
                .and(PROVIDER_CAPABILITIES.STATUS.in("SANDBOX", "LIVE"))
                .execute());
        providerAccounts.register();
    }
}
