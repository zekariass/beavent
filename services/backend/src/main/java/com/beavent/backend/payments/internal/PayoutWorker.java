package com.beavent.backend.payments.internal;

import com.beavent.backend.payments.Payouts;
import com.beavent.backend.payments.Refunds;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Releases delivered sales and runs the payouts on a timer (docs/04 §9); both are safe to repeat. */
@Component
class PayoutWorker {

    private static final Logger log = LoggerFactory.getLogger(PayoutWorker.class);

    private final Payouts payouts;
    private final Refunds refunds;

    PayoutWorker(Payouts payouts, Refunds refunds) {
        this.payouts = payouts;
        this.refunds = refunds;
    }

    @Scheduled(
            fixedDelayString = "${beavent.payouts.worker-interval-ms:300000}",
            initialDelayString = "${beavent.payouts.worker-interval-ms:300000}")
    void run() {
        Instant now = Instant.now();
        try {
            int released = payouts.releaseDue(now);
            int settled = refunds.sweep(now);
            payouts.sweepVenueFees(now);
            int created = payouts.createDue(now);
            int moved = refunds.processApiRefunds(now);
            if (released + created + settled + moved > 0) {
                log.info(
                        "Released {} sales, settled {} orders, created {} payouts, moved {} refunds",
                        released,
                        settled,
                        created,
                        moved);
            }
        } catch (RuntimeException e) {
            log.warn("Payout worker failed; it runs again shortly", e);
        }
    }
}
