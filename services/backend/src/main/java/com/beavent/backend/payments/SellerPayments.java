package com.beavent.backend.payments;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * How a business gets paid (plan 3.4): the payout account the money goes to, and whether the business may take online
 * payments yet. Online payments open only when the business is verified (KYB) and has a verified payout account; in
 * Chapa mode also a subaccount at Chapa made from it. Changing the account starts a 72-hour cooling-off before
 * payouts go to it, with a message to the people who manage payouts.
 */
public interface SellerPayments {

    /** A bank or wallet a payout can go to. */
    record Bank(String code, String kind, Map<String, String> names) {}

    /** A payout account as screens show it: the number is only ever masked. */
    record Account(
            UUID id,
            String kind,
            String institutionCode,
            String institutionName,
            String accountName,
            String accountNumberMasked,
            String status,
            boolean isDefault,
            Instant payoutsAllowedAfter,
            Instant reviewedAt,
            String decisionNote) {}

    /**
     * Why a business cannot take online payments yet: {@code ORGANIZATION_NOT_VERIFIED}, {@code NO_PAYOUT_ACCOUNT},
     * {@code PAYOUT_ACCOUNT_IN_REVIEW}, {@code PAYOUT_ACCOUNT_REJECTED} or {@code SUBACCOUNT_MISSING} (Chapa mode).
     */
    record Setup(
            String provider,
            String collectionMode,
            boolean ready,
            boolean enforced,
            List<String> blockers,
            String organizationStatus,
            UUID legalEntityId,
            String legalEntityName,
            Account active,
            Account pending,
            Account rejected) {}

    /** What a seller types in: the number and holder name, for a bank or wallet from {@link #banks}. */
    record NewAccount(UUID legalEntityId, String institutionCode, String accountName, String accountNumber) {}

    List<Bank> banks(String countryCode);

    Setup setup(UUID orgId);

    /**
     * Adds a payout account for review. The holder name must match the business's registered name; one account waits
     * for review at a time. Call after the caller proved who they are with a fresh code (step-up).
     */
    Account addAccount(UUID orgId, UUID userId, NewAccount account);

    /** Withdraws the account that is waiting for review. */
    void cancelPending(UUID orgId, UUID userId, UUID accountId);

    /**
     * Whether new bookings may ask for payment online: true when the business is ready, or when readiness is not
     * required in this deployment ({@code beavent.payments.require-seller-readiness}, off only for local development).
     */
    boolean available(UUID orgId);

    /**
     * The full account number, for the staff member checking it against the bank's letter (the caller proves who they
     * are with a fresh code and records why). Never sent to sellers' screens, which only show it masked.
     */
    String accountNumber(UUID accountId);

    /**
     * Platform staff's decision on an account waiting for review. Approving makes it the business's default account
     * (the earlier one is retired) and, in Chapa mode, creates the business's subaccount; rejecting keeps the note for
     * the seller.
     *
     * @throws com.beavent.backend.platform.ApiException {@code CONFLICT} when it was already decided
     */
    Account decide(UUID accountId, UUID reviewerUserId, boolean approve, String note);
}
