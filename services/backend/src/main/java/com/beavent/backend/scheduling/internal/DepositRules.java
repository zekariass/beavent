package com.beavent.backend.scheduling.internal;

import com.beavent.backend.platform.MoneyMath;
import java.math.BigDecimal;

/**
 * What a customer pays up front to book (docs/04 §6.3), pure. The business's booking policy sets a deposit (a percentage
 * of the all-in price, a fixed amount, or the full price) for everyone or for some customers; a service that cannot be
 * paid at the venue is paid in full online. The platform's buyer fees are always collected online with the deposit.
 */
final class DepositRules {

    private DepositRules() {}

    /** What the customer pays now and what is left for the venue; {@code required} is false when nothing is due online. */
    record Terms(boolean required, long depositMinor, long atVenueMinor, long totalMinor) {
        static Terms none(long totalMinor) {
            return new Terms(false, 0, totalMinor, totalMinor);
        }
    }

    /** What the business knows about the customer, for "new customers" and "flagged customers" deposits. */
    record Customer(boolean isNew, boolean marketplaceAcquired, boolean flagged) {
        /** A person not signed in yet: treated as new, never flagged. */
        static final Customer UNKNOWN = new Customer(true, false, false);
    }

    /** Two no-shows with a business mark a customer (docs/04 §6.3: repeat no-shows flag the customer). */
    static final int FLAGGED_NO_SHOWS = 2;

    static boolean applies(String appliesTo, Customer customer) {
        return switch (appliesTo == null ? "EVERYONE" : appliesTo) {
            case "NEW_CUSTOMERS" -> customer.isNew();
            case "MARKETPLACE_CUSTOMERS" -> customer.marketplaceAcquired();
            case "FLAGGED_CUSTOMERS" -> customer.flagged();
            default -> true;
        };
    }

    /**
     * @param totalMinor the all-in price (what the customer pays in the end, fees included)
     * @param buyerFeesMinor the platform's fees inside it, collected online with the deposit
     */
    static Terms terms(Bookings.Policy policy, long totalMinor, long buyerFeesMinor, Customer customer) {
        if (totalMinor <= 0) return Terms.none(totalMinor);
        long deposit;
        if (!policy.allowPayAtVenue()) {
            deposit = totalMinor; // some service here cannot be paid at the venue
        } else if (!applies(policy.depositAppliesTo(), customer)) {
            return Terms.none(totalMinor);
        } else {
            deposit = switch (policy.depositMode() == null ? "NONE" : policy.depositMode()) {
                case "PERCENT" ->
                    MoneyMath.ofRate(
                            totalMinor, policy.depositPercent().movePointLeft(2).max(BigDecimal.ZERO));
                case "FIXED" -> policy.depositFixedMinor() == null ? 0 : policy.depositFixedMinor();
                case "FULL" -> totalMinor;
                default -> 0;
            };
            if (deposit <= 0) return Terms.none(totalMinor);
        }
        deposit = Math.min(Math.max(deposit, buyerFeesMinor), totalMinor);
        return new Terms(true, deposit, totalMinor - deposit, totalMinor);
    }
}
