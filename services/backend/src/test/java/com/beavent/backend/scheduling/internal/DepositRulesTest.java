package com.beavent.backend.scheduling.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.beavent.backend.scheduling.internal.DepositRules.Customer;
import com.beavent.backend.scheduling.internal.DepositRules.Terms;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Who pays how much up front (docs/04 §6.3). */
class DepositRulesTest {

    private static final Customer NEW = new Customer(true, false, false);
    private static final Customer RETURNING = new Customer(false, false, false);

    private static Bookings.Policy policy(
            String mode, String percent, Long fixed, String appliesTo, boolean payAtVenue) {
        return new Bookings.Policy(
                false,
                payAtVenue,
                mode,
                percent == null ? null : new BigDecimal(percent),
                fixed,
                1440,
                Instant.now(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                appliesTo);
    }

    @Test
    void aPercentageDepositIsTakenFromTheAllInPrice() {
        Terms t = DepositRules.terms(policy("PERCENT", "20", null, "EVERYONE", true), 180_000, 0, NEW);
        assertThat(t.required()).isTrue();
        assertThat(t.depositMinor()).isEqualTo(36_000);
        assertThat(t.atVenueMinor()).isEqualTo(144_000);
    }

    @Test
    void fixedAndFullDepositsAreCappedAtThePrice() {
        assertThat(DepositRules.terms(policy("FIXED", null, 50_000L, "EVERYONE", true), 180_000, 0, NEW)
                        .depositMinor())
                .isEqualTo(50_000);
        assertThat(DepositRules.terms(policy("FIXED", null, 500_000L, "EVERYONE", true), 180_000, 0, NEW)
                        .depositMinor())
                .isEqualTo(180_000);
        Terms full = DepositRules.terms(policy("FULL", null, null, "EVERYONE", true), 180_000, 0, NEW);
        assertThat(full.depositMinor()).isEqualTo(180_000);
        assertThat(full.atVenueMinor()).isZero();
    }

    @Test
    void theBuyerFeeIsAlwaysCollectedWithTheDeposit() {
        // 10 % of 100,000 = 10,000, but the buyer fee of 12,000 goes online with it.
        assertThat(DepositRules.terms(policy("PERCENT", "10", null, "EVERYONE", true), 100_000, 12_000, NEW)
                        .depositMinor())
                .isEqualTo(12_000);
    }

    @Test
    void whoPaysFollowsTheCustomer() {
        Bookings.Policy newOnly = policy("PERCENT", "20", null, "NEW_CUSTOMERS", true);
        assertThat(DepositRules.terms(newOnly, 100_000, 0, NEW).required()).isTrue();
        assertThat(DepositRules.terms(newOnly, 100_000, 0, RETURNING).required())
                .isFalse();
        Bookings.Policy marketplace = policy("PERCENT", "20", null, "MARKETPLACE_CUSTOMERS", true);
        assertThat(DepositRules.terms(marketplace, 100_000, 0, new Customer(true, true, false))
                        .required())
                .isTrue();
        assertThat(DepositRules.terms(marketplace, 100_000, 0, NEW).required()).isFalse();
        Bookings.Policy flagged = policy("PERCENT", "20", null, "FLAGGED_CUSTOMERS", true);
        assertThat(DepositRules.terms(flagged, 100_000, 0, new Customer(false, false, true))
                        .required())
                .isTrue();
        assertThat(DepositRules.terms(flagged, 100_000, 0, NEW).required()).isFalse();
        // A person not signed in yet is treated as new and never flagged.
        assertThat(DepositRules.terms(newOnly, 100_000, 0, Customer.UNKNOWN).required())
                .isTrue();
        assertThat(DepositRules.terms(flagged, 100_000, 0, Customer.UNKNOWN).required())
                .isFalse();
    }

    @Test
    void noDepositMeansNothingOnlineUnlessTheServiceCannotBePaidAtTheVenue() {
        assertThat(DepositRules.terms(policy("NONE", null, null, "EVERYONE", true), 100_000, 0, NEW)
                        .required())
                .isFalse();
        // A service that cannot be paid at the venue is paid in full online, whoever the customer is.
        Terms t = DepositRules.terms(policy("NONE", null, null, "NEW_CUSTOMERS", false), 100_000, 0, RETURNING);
        assertThat(t.required()).isTrue();
        assertThat(t.depositMinor()).isEqualTo(100_000);
        // Nothing to pay, nothing to collect.
        assertThat(DepositRules.terms(policy("FULL", null, null, "EVERYONE", true), 0, 0, NEW)
                        .required())
                .isFalse();
        // Older appointments carry no "applies to": everyone.
        assertThat(DepositRules.terms(policy("FULL", null, null, null, true), 5_000, 0, RETURNING)
                        .required())
                .isTrue();
    }

    @Test
    void forAnyPriceThePartsAddUpAndTheDepositNeverExceedsThePrice() {
        Random random = new Random(33);
        for (int i = 0; i < 5_000; i++) {
            long total = random.nextLong(1, 50_000_000);
            long fees = random.nextBoolean() ? 0 : random.nextLong(0, total + 1_000);
            String mode = new String[] {"PERCENT", "FIXED", "FULL"}[random.nextInt(3)];
            Terms t = DepositRules.terms(
                    policy(
                            mode,
                            mode.equals("PERCENT") ? String.valueOf(random.nextInt(1, 101)) : null,
                            mode.equals("FIXED") ? random.nextLong(1, 60_000_000) : null,
                            "EVERYONE",
                            true),
                    total,
                    fees,
                    NEW);
            assertThat(t.required()).as("run %d", i).isTrue();
            assertThat(t.depositMinor()).as("run %d", i).isBetween(1L, total);
            assertThat(t.depositMinor()).isGreaterThanOrEqualTo(Math.min(fees, total));
            assertThat(t.depositMinor() + t.atVenueMinor()).isEqualTo(total);
        }
    }
}
