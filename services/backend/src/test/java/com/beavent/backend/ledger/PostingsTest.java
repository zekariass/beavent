package com.beavent.backend.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beavent.backend.ledger.Ledger.Account;
import com.beavent.backend.ledger.Ledger.Code;
import com.beavent.backend.ledger.Ledger.Line;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** docs/04 §10 as arithmetic: every posting balances, and the worked example comes out as the document says. */
class PostingsTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final Account CLEARING = Account.clearing(UUID.randomUUID());

    private static void apply(Map<String, Long> books, List<Line> lines) {
        assertThat(lines.stream().mapToLong(Line::amountMinor).sum())
                .as("balanced")
                .isZero();
        assertThat(lines).allSatisfy(l -> assertThat(l.amountMinor()).isNotZero());
        for (Line l : lines) books.merge(l.account().code().name(), l.amountMinor(), Long::sum);
    }

    @Test
    void theWorkedExampleOfTheDesignDocument() {
        // Br 1,500.00 appointment, platform fee Br 150.00 and VAT Br 22.50, all in minor units.
        Map<String, Long> books = new HashMap<>();
        for (var p : Postings.captured(false, ORG, CLEARING, 150_000, 15_000, 2_250)) apply(books, p.lines());
        apply(books, Postings.released(ORG, 150_000 - 17_250).lines());
        apply(books, Postings.payoutInitiated(ORG, 132_750).lines());
        apply(books, Postings.payoutSettled(CLEARING, 132_750).lines());

        // The seller's accounts net to zero; the provider holds exactly the platform's money.
        assertThat(books.getOrDefault("SELLER_PENDING", 0L)).isZero();
        assertThat(books.getOrDefault("SELLER_AVAILABLE", 0L)).isZero();
        assertThat(books.get("PAYOUTS_IN_TRANSIT")).isZero();
        assertThat(books.get("PLATFORM_FEE_REVENUE")).isEqualTo(-15_000);
        assertThat(books.get("TAX_PAYABLE")).isEqualTo(-2_250);
        assertThat(books.get("PROVIDER_CLEARING")).as("fee + VAT, as debit").isEqualTo(17_250);
    }

    @Test
    void splitAtSourceOnlyRecordsThePlatformShare() {
        var planned = Postings.captured(true, ORG, CLEARING, 150_000, 15_000, 2_250);
        assertThat(planned).hasSize(1);
        Map<String, Long> books = new HashMap<>();
        apply(books, planned.get(0).lines());
        assertThat(books).containsEntry("PROVIDER_CLEARING", 17_250L).doesNotContainKey("SELLER_PENDING");
        // No fees, nothing arrives.
        assertThat(Postings.captured(true, ORG, CLEARING, 150_000, 0, 0)).isEmpty();
    }

    @Test
    void aPaymentWithoutFeesIsOneTransactionAndTheSellerOwnsAllOfIt() {
        var planned = Postings.captured(false, ORG, CLEARING, 10_000, 0, 0);
        assertThat(planned)
                .hasSize(1)
                .first()
                .satisfies(p -> assertThat(p.kind()).isEqualTo("PAYMENT_CAPTURED"));
    }

    @Test
    void moneyThatIsNotTheSellersIsOwedBackToThePayer() {
        Map<String, Long> books = new HashMap<>();
        apply(books, Postings.receivedForRefund(CLEARING, 10_000).lines());
        assertThat(books).containsEntry("PROVIDER_CLEARING", 10_000L).containsEntry("REFUNDS_PAYABLE", -10_000L);
    }

    @Test
    void aFailedPayoutPutsTheMoneyBackAndAReturnedOneToo() {
        Map<String, Long> books = new HashMap<>();
        apply(books, Postings.payoutInitiated(ORG, 5_000).lines());
        apply(books, Postings.payoutFailed(ORG, 5_000).lines());
        assertThat(books.get("SELLER_AVAILABLE")).isZero();
        assertThat(books.get("PAYOUTS_IN_TRANSIT")).isZero();

        Map<String, Long> returned = new HashMap<>();
        apply(returned, Postings.payoutInitiated(ORG, 5_000).lines());
        apply(returned, Postings.payoutSettled(CLEARING, 5_000).lines());
        apply(returned, Postings.payoutReturned(CLEARING, ORG, 5_000).lines());
        assertThat(returned.get("SELLER_AVAILABLE")).isZero();
        assertThat(returned.get("PROVIDER_CLEARING")).isZero();
    }

    @Test
    void badAmountsAreRefused() {
        assertThatThrownBy(() -> Postings.captured(false, ORG, CLEARING, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Postings.captured(false, ORG, CLEARING, 100, 80, 30))
                .as("fees above the payment")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Postings.captured(false, ORG, CLEARING, 100, -1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Postings.released(ORG, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anyMixOfSalesReleasesAndPayoutsKeepsTheBooksStraight() {
        Random random = new Random(7);
        for (int round = 0; round < 2_000; round++) {
            Map<String, Long> books = new HashMap<>();
            long gross = 1 + random.nextInt(5_000_000);
            long fee = random.nextInt((int) Math.min(gross, 500_000) + 1);
            long vat = random.nextInt((int) Math.min(gross - fee, 100_000) + 1);
            for (var p : Postings.captured(false, ORG, CLEARING, gross, fee, vat)) apply(books, p.lines());
            long net = gross - fee - vat;
            if (net > 0) {
                apply(books, Postings.released(ORG, net).lines());
                long paidOut = 1 + (long) (random.nextDouble() * net);
                apply(books, Postings.payoutInitiated(ORG, paidOut).lines());
                if (random.nextBoolean())
                    apply(books, Postings.payoutSettled(CLEARING, paidOut).lines());
                else apply(books, Postings.payoutFailed(ORG, paidOut).lines());
            }
            long all = books.values().stream().mapToLong(Long::longValue).sum();
            assertThat(all).as("the whole ledger sums to zero").isZero();
            // The provider holds exactly what the seller is still owed plus the platform's own share.
            long owed = -(books.getOrDefault("SELLER_PENDING", 0L) + books.getOrDefault("SELLER_AVAILABLE", 0L));
            assertThat(books.getOrDefault("PROVIDER_CLEARING", 0L)).isEqualTo(owed + fee + vat);
            assertThat(books.getOrDefault("PAYOUTS_IN_TRANSIT", 0L))
                    .as("nothing is left on the way")
                    .isZero();
            assertThat(new ArrayList<>(books.keySet())).doesNotContain(Code.SUSPENSE.name());
        }
    }
}
