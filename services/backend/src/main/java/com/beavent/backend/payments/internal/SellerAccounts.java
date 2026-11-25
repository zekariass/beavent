package com.beavent.backend.payments.internal;

import static com.beavent.db.payments.Tables.PAYOUT_ACCOUNTS;
import static com.beavent.db.payments.Tables.PROVIDER_ACCOUNTS;
import static com.beavent.db.ref.Tables.BANKS;
import static com.beavent.db.tenant.Tables.LEGAL_ENTITIES;
import static com.beavent.db.tenant.Tables.ORGANIZATIONS;

import com.beavent.backend.payments.SellerPayments;
import com.beavent.backend.payments.internal.provider.PaymentProviderAdapter;
import com.beavent.backend.payments.internal.provider.ProviderException;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.Audit;
import com.beavent.backend.platform.DomainEvent;
import com.beavent.backend.platform.Outbox;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.SecretBox;
import com.beavent.backend.platform.SystemDb;
import com.beavent.db.payments.tables.records.PayoutAccountsRecord;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link SellerPayments}: a business's payout account and whether it may take online payments (docs/07 3.4). Runs with
 * the system role; the web layer has already checked who may ask and whether they confirmed it is them.
 */
@Service
class SellerAccounts implements SellerPayments {

    private static final Logger log = LoggerFactory.getLogger(SellerAccounts.class);
    private static final Pattern BANK_NUMBER = Pattern.compile("\\d{6,20}");
    private static final Pattern ET_MOBILE = Pattern.compile("(?:\\+?251|0)([79]\\d{8})");

    private final DSLContext system;
    private final TransactionTemplate transactions;
    private final ActiveProvider providers;
    private final ProviderAccounts accounts;
    private final PaymentsProperties props;
    private final SecretBox box;
    private final Outbox outbox;
    private final Audit audit;
    private final JsonMapper json;

    SellerAccounts(
            @SystemDb DSLContext system,
            @SystemDb TransactionTemplate transactions,
            ActiveProvider providers,
            ProviderAccounts accounts,
            PaymentsProperties props,
            SecretBox box,
            Outbox outbox,
            Audit audit,
            JsonMapper json) {
        this.system = system;
        this.transactions = transactions;
        this.providers = providers;
        this.accounts = accounts;
        this.props = props;
        this.box = box;
        this.outbox = outbox;
        this.audit = audit;
        this.json = json;
    }

    @Override
    public List<Bank> banks(String countryCode) {
        return system.selectFrom(BANKS)
                .where(BANKS.COUNTRY_CODE.eq(countryCode), BANKS.IS_ACTIVE.isTrue())
                .orderBy(BANKS.SORT_ORDER, BANKS.CODE)
                .fetch(b ->
                        new Bank(b.getCode(), b.getKind(), names(b.getNameI18n().data())));
    }

    @Override
    public Setup setup(UUID orgId) {
        String organization = system.select(ORGANIZATIONS.VERIFICATION_STATUS)
                .from(ORGANIZATIONS)
                .where(ORGANIZATIONS.ID.eq(orgId))
                .fetchSingle(ORGANIZATIONS.VERIFICATION_STATUS);
        var entity = system.selectFrom(LEGAL_ENTITIES)
                .where(LEGAL_ENTITIES.ORG_ID.eq(orgId))
                .orderBy(LEGAL_ENTITIES.IS_DEFAULT.desc(), LEGAL_ENTITIES.CREATED_AT)
                .limit(1)
                .fetchOne();
        List<PayoutAccountsRecord> rows = system.selectFrom(PAYOUT_ACCOUNTS)
                .where(PAYOUT_ACCOUNTS.ORG_ID.eq(orgId), PAYOUT_ACCOUNTS.STATUS.eq("ACTIVE"))
                .orderBy(PAYOUT_ACCOUNTS.CREATED_AT.desc())
                .fetch();
        PayoutAccountsRecord active = rows.stream()
                .filter(r -> r.getIsDefault() && r.getVerificationStatus().equals("VERIFIED"))
                .findFirst()
                .orElse(null);
        PayoutAccountsRecord pending = rows.stream()
                .filter(r -> r.getVerificationStatus().equals("PENDING"))
                .findFirst()
                .orElse(null);
        // A rejection is shown until a newer account is added or approved.
        PayoutAccountsRecord rejected = rows.stream()
                .filter(r -> r.getVerificationStatus().equals("FAILED"))
                .findFirst()
                .filter(r ->
                        pending == null && (active == null || r.getCreatedAt().isAfter(active.getCreatedAt())))
                .orElse(null);
        PaymentProviderAdapter adapter = providers.adapter();
        boolean split = adapter.collectionMode().equals("PROVIDER_SPLIT");
        List<String> blockers = new ArrayList<>();
        if (!organization.equals("VERIFIED")) blockers.add("ORGANIZATION_NOT_VERIFIED");
        if (active == null) {
            blockers.add(
                    pending != null
                            ? "PAYOUT_ACCOUNT_IN_REVIEW"
                            : rejected != null ? "PAYOUT_ACCOUNT_REJECTED" : "NO_PAYOUT_ACCOUNT");
        } else if (split && subAccountOf(orgId) == null) {
            blockers.add("SUBACCOUNT_MISSING");
        }
        return new Setup(
                adapter.providerCode(),
                adapter.collectionMode(),
                blockers.isEmpty(),
                props.requireSellerReadiness(),
                blockers,
                organization,
                entity == null ? null : entity.getId(),
                entity == null ? null : entity.getRegisteredName(),
                account(active),
                account(pending),
                account(rejected));
    }

    @Override
    public boolean available(UUID orgId) {
        return !props.requireSellerReadiness() || setup(orgId).ready();
    }

    @Override
    public Account addAccount(UUID orgId, UUID userId, NewAccount in) {
        PayoutAccountsRecord created = transactions.execute(status -> {
            var entity = in.legalEntityId() != null
                    ? system.selectFrom(LEGAL_ENTITIES)
                            .where(LEGAL_ENTITIES.ID.eq(in.legalEntityId()), LEGAL_ENTITIES.ORG_ID.eq(orgId))
                            .fetchOne()
                    : system.selectFrom(LEGAL_ENTITIES)
                            .where(LEGAL_ENTITIES.ORG_ID.eq(orgId))
                            .orderBy(LEGAL_ENTITIES.IS_DEFAULT.desc(), LEGAL_ENTITIES.CREATED_AT)
                            .limit(1)
                            .fetchOne();
            if (entity == null) {
                throw new ApiException(ProblemCode.CONSTRAINT_VIOLATION, "Add your business's legal details first.");
            }
            var bank = system.selectFrom(BANKS)
                    .where(
                            BANKS.COUNTRY_CODE.eq(entity.getCountryCode()),
                            BANKS.CODE.eq(in.institutionCode()),
                            BANKS.IS_ACTIVE.isTrue())
                    .fetchOne();
            if (bank == null) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "Choose one of the listed banks or wallets.");
            }
            String number = normalizeNumber(bank.getKind(), in.accountNumber());
            if (!sameName(in.accountName(), entity.getRegisteredName())) {
                throw new ApiException(
                        ProblemCode.CONSTRAINT_VIOLATION,
                        "The account holder's name must match your registered business name.");
            }
            system.selectFrom(PAYOUT_ACCOUNTS)
                    .where(PAYOUT_ACCOUNTS.ORG_ID.eq(orgId))
                    .forUpdate()
                    .fetch();
            if (system.fetchExists(
                    PAYOUT_ACCOUNTS,
                    PAYOUT_ACCOUNTS
                            .ORG_ID
                            .eq(orgId)
                            .and(PAYOUT_ACCOUNTS.STATUS.eq("ACTIVE"))
                            .and(PAYOUT_ACCOUNTS.VERIFICATION_STATUS.eq("PENDING")))) {
                throw new ApiException(
                        ProblemCode.CONFLICT, "A payout account is already waiting for review. Withdraw it first.");
            }
            PayoutAccountsRecord row = system.newRecord(PAYOUT_ACCOUNTS);
            row.setOrgId(orgId);
            row.setLegalEntityId(entity.getId());
            row.setKind("BANK".equals(bank.getKind()) ? "BANK_ACCOUNT" : "MOBILE_MONEY");
            row.setCountryCode(entity.getCountryCode());
            row.setCurrencyCode(entity.getFunctionalCurrency());
            row.setInstitutionCode(bank.getCode());
            row.setInstitutionName(names(bank.getNameI18n().data()).get("en"));
            row.setAccountName(in.accountName().strip());
            row.setAccountNumberMasked(SecretBox.mask(number));
            row.setDetailsSecretRef(box.seal(number));
            row.setVerificationStatus("PENDING");
            row.setCreatedBy(userId);
            row.store();
            row.refresh();
            outbox.publish(new DomainEvent(
                    "payments.payout_account",
                    row.getId(),
                    "payout_account.changed",
                    1,
                    orgId,
                    Map.of(
                            "payoutAccountId", row.getId().toString(),
                            "institutionName", row.getInstitutionName(),
                            "last4", row.getAccountNumberMasked(),
                            "allowedAfter",
                                    row.getPayoutsAllowedAfter().toInstant().toString(),
                            "changedBy", userId.toString())));
            audit.record(new Audit.Entry(
                    Audit.Actor.USER,
                    userId,
                    orgId,
                    "payout_account.added",
                    "payments.payout_account",
                    row.getId().toString(),
                    null,
                    Map.of("institution", row.getInstitutionName(), "number", row.getAccountNumberMasked()),
                    null));
            return row;
        });
        return account(created);
    }

    @Override
    public void cancelPending(UUID orgId, UUID userId, UUID accountId) {
        transactions.executeWithoutResult(status -> {
            int closed = system.update(PAYOUT_ACCOUNTS)
                    .set(PAYOUT_ACCOUNTS.STATUS, "DISABLED")
                    .set(PAYOUT_ACCOUNTS.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                    .where(
                            PAYOUT_ACCOUNTS.ID.eq(accountId),
                            PAYOUT_ACCOUNTS.ORG_ID.eq(orgId),
                            PAYOUT_ACCOUNTS.VERIFICATION_STATUS.eq("PENDING"),
                            PAYOUT_ACCOUNTS.STATUS.eq("ACTIVE"))
                    .execute();
            if (closed == 0) throw new ApiException(ProblemCode.NOT_FOUND, "No account is waiting for review.");
            audit.record(new Audit.Entry(
                    Audit.Actor.USER,
                    userId,
                    orgId,
                    "payout_account.withdrawn",
                    "payments.payout_account",
                    accountId.toString(),
                    null,
                    null,
                    null));
        });
    }

    @Override
    public String accountNumber(UUID accountId) {
        String sealed = system.select(PAYOUT_ACCOUNTS.DETAILS_SECRET_REF)
                .from(PAYOUT_ACCOUNTS)
                .where(PAYOUT_ACCOUNTS.ID.eq(accountId))
                .fetchOne(PAYOUT_ACCOUNTS.DETAILS_SECRET_REF);
        if (sealed == null) throw new ApiException(ProblemCode.NOT_FOUND);
        return box.open(sealed);
    }

    @Override
    public Account decide(UUID accountId, UUID reviewerUserId, boolean approve, String note) {
        PayoutAccountsRecord decided = transactions.execute(status -> {
            PayoutAccountsRecord row = system.selectFrom(PAYOUT_ACCOUNTS)
                    .where(PAYOUT_ACCOUNTS.ID.eq(accountId))
                    .forUpdate()
                    .fetchOne();
            if (row == null) throw new ApiException(ProblemCode.NOT_FOUND);
            if (!row.getVerificationStatus().equals("PENDING")
                    || !row.getStatus().equals("ACTIVE")) {
                throw new ApiException(ProblemCode.CONFLICT, "This account has already been decided.");
            }
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            if (approve) {
                // The earlier default is retired: the business is paid into one account at a time.
                system.update(PAYOUT_ACCOUNTS)
                        .set(PAYOUT_ACCOUNTS.IS_DEFAULT, false)
                        .set(PAYOUT_ACCOUNTS.STATUS, "DISABLED")
                        .set(PAYOUT_ACCOUNTS.UPDATED_AT, now)
                        .where(
                                PAYOUT_ACCOUNTS.ORG_ID.eq(row.getOrgId()),
                                PAYOUT_ACCOUNTS.CURRENCY_CODE.eq(row.getCurrencyCode()),
                                PAYOUT_ACCOUNTS.IS_DEFAULT.isTrue())
                        .execute();
                row.setVerificationStatus("VERIFIED");
                row.setVerifiedVia("MANUAL");
                row.setIsDefault(true);
            } else {
                row.setVerificationStatus("FAILED");
            }
            row.setReviewedBy(reviewerUserId);
            row.setReviewedAt(now);
            row.setDecisionNote(note == null ? null : note.strip());
            row.setUpdatedAt(now);
            row.store();
            outbox.publish(new DomainEvent(
                    "payments.payout_account",
                    row.getId(),
                    approve ? "payout_account.verified" : "payout_account.rejected",
                    1,
                    row.getOrgId(),
                    Map.of("payoutAccountId", row.getId().toString())));
            return row;
        });
        if (approve) {
            try {
                ensureSubAccount(decided.getOrgId());
            } catch (RuntimeException e) {
                // The account is verified; the subaccount is retried when the first payment starts.
                log.warn("Could not create the subaccount of {}: {}", decided.getOrgId(), e.getMessage());
            }
        }
        return account(decided);
    }

    // --- Chapa mode: the seller's subaccount ---------------------------------------------------------------------

    /** The seller's subaccount at the provider, or null. */
    String subAccountOf(UUID orgId) {
        return system.select(PROVIDER_ACCOUNTS.SUB_ACCOUNT_REFERENCE)
                .from(PROVIDER_ACCOUNTS)
                .where(
                        PROVIDER_ACCOUNTS.PROVIDER_CODE.eq(providers.code()),
                        PROVIDER_ACCOUNTS.ORG_ID.eq(orgId),
                        PROVIDER_ACCOUNTS.PARENT_ACCOUNT_ID.isNotNull(),
                        PROVIDER_ACCOUNTS.STATUS.eq("ACTIVE"))
                .limit(1)
                .fetchOne(PROVIDER_ACCOUNTS.SUB_ACCOUNT_REFERENCE);
    }

    /**
     * Makes sure the seller has a subaccount (Chapa mode): created at the provider from the verified payout account,
     * then recorded. A bank the provider has no code for leaves the business without one (shown as a blocker).
     */
    String ensureSubAccount(UUID orgId) {
        PaymentProviderAdapter adapter = providers.adapter();
        if (!adapter.collectionMode().equals("PROVIDER_SPLIT")) return null;
        String existing = subAccountOf(orgId);
        if (existing != null) return existing;
        PayoutAccountsRecord payout = system.selectFrom(PAYOUT_ACCOUNTS)
                .where(
                        PAYOUT_ACCOUNTS.ORG_ID.eq(orgId),
                        PAYOUT_ACCOUNTS.IS_DEFAULT.isTrue(),
                        PAYOUT_ACCOUNTS.STATUS.eq("ACTIVE"),
                        PAYOUT_ACCOUNTS.VERIFICATION_STATUS.eq("VERIFIED"))
                .fetchOne();
        if (payout == null) return null;
        var bank = system.selectFrom(BANKS)
                .where(BANKS.COUNTRY_CODE.eq(payout.getCountryCode()), BANKS.CODE.eq(payout.getInstitutionCode()))
                .fetchOne();
        JsonNode codes =
                bank == null ? null : json.readTree(bank.getProviderCodes().data());
        String bankCode =
                codes == null ? "" : codes.path(adapter.providerCode()).asString("");
        if (bankCode.isBlank()) {
            log.warn(
                    "{} has no code for {}: no subaccount for {}",
                    adapter.providerCode(),
                    payout.getInstitutionCode(),
                    orgId);
            return null;
        }
        String businessName = system.select(ORGANIZATIONS.DISPLAY_NAME)
                .from(ORGANIZATIONS)
                .where(ORGANIZATIONS.ID.eq(orgId))
                .fetchSingle(ORGANIZATIONS.DISPLAY_NAME);
        String reference;
        try {
            reference = adapter.createSubAccount(
                    businessName, payout.getAccountName(), bankCode, box.open(payout.getDetailsSecretRef()));
        } catch (ProviderException e) {
            throw new ApiException(ProblemCode.PAYMENT_UNAVAILABLE, e.getMessage());
        }
        UUID market = system.select(ORGANIZATIONS.HOME_MARKET_ID)
                .from(ORGANIZATIONS)
                .where(ORGANIZATIONS.ID.eq(orgId))
                .fetchSingle(ORGANIZATIONS.HOME_MARKET_ID);
        UUID parent = accounts.platformAccount(adapter.providerCode()).orElseThrow();
        transactions.executeWithoutResult(status -> system.insertInto(PROVIDER_ACCOUNTS)
                .set(PROVIDER_ACCOUNTS.PROVIDER_CODE, adapter.providerCode())
                .set(PROVIDER_ACCOUNTS.OWNER, "ORGANIZATION")
                .set(PROVIDER_ACCOUNTS.ORG_ID, orgId)
                .set(PROVIDER_ACCOUNTS.MARKET_ID, market)
                .set(PROVIDER_ACCOUNTS.ENVIRONMENT, providers.environment())
                .set(PROVIDER_ACCOUNTS.DISPLAY_NAME, businessName + " (" + adapter.providerCode() + " subaccount)")
                .set(PROVIDER_ACCOUNTS.PARENT_ACCOUNT_ID, parent)
                .set(PROVIDER_ACCOUNTS.SUB_ACCOUNT_REFERENCE, reference)
                .set(
                        PROVIDER_ACCOUNTS.SETTLEMENT_CURRENCY,
                        payout.getCurrencyCode().trim())
                .set(PROVIDER_ACCOUNTS.STATUS, "ACTIVE")
                .execute());
        return reference;
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    private Account account(PayoutAccountsRecord r) {
        if (r == null) return null;
        return new Account(
                r.getId(),
                r.getKind(),
                r.getInstitutionCode(),
                r.getInstitutionName(),
                r.getAccountName(),
                r.getAccountNumberMasked(),
                r.getVerificationStatus(),
                r.getIsDefault(),
                r.getPayoutsAllowedAfter() == null
                        ? null
                        : r.getPayoutsAllowedAfter().toInstant(),
                r.getReviewedAt() == null ? null : r.getReviewedAt().toInstant(),
                r.getDecisionNote());
    }

    private Map<String, String> names(String jsonText) {
        Map<String, String> out = new LinkedHashMap<>();
        json.readTree(jsonText)
                .properties()
                .forEach(e -> out.put(e.getKey(), e.getValue().asString()));
        return out;
    }

    /** Digits for a bank account; an Ethiopian mobile number in 2519… form for a wallet. */
    static String normalizeNumber(String kind, String raw) {
        String text = raw == null ? "" : raw.replaceAll("[\\s-]", "");
        if (kind.equals("MOBILE_MONEY")) {
            var m = ET_MOBILE.matcher(text);
            if (!m.matches()) {
                throw new ApiException(
                        ProblemCode.VALIDATION_FAILED, "Enter the wallet's mobile number, like 0911 223 344.");
            }
            return "251" + m.group(1);
        }
        if (!BANK_NUMBER.matcher(text).matches()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "An account number has 6 to 20 digits.");
        }
        return text;
    }

    /** The holder's name is the business's registered name, ignoring case, punctuation and "PLC"-style endings. */
    static boolean sameName(String holder, String registered) {
        return !normalizeName(holder).isEmpty() && normalizeName(holder).equals(normalizeName(registered));
    }

    static String normalizeName(String name) {
        return name.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .replaceAll("\\b(plc|p l c|private limited company|share company|sc|ltd|limited|co)\\b", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }
}
