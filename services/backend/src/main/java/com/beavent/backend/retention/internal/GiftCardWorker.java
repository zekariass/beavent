package com.beavent.backend.retention.internal;

import com.beavent.backend.retention.GiftCards;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Delivers paid cards when they are due, writes off expired ones and closes orders nobody paid (every minute). */
@Component
class GiftCardWorker {

    private static final Logger log = LoggerFactory.getLogger(GiftCardWorker.class);

    private final GiftCards cards;

    GiftCardWorker(GiftCards cards) {
        this.cards = cards;
    }

    @Scheduled(
            fixedDelayString = "${beavent.retention.worker-interval-ms:60000}",
            initialDelayString = "${beavent.retention.worker-interval-ms:60000}")
    void run() {
        Instant now = Instant.now();
        try {
            int sent = cards.deliverDue(now);
            int expired = cards.expireDue(now);
            int closed = cards.closeUnpaid(now);
            if (sent + expired + closed > 0) {
                log.info("Delivered {} gift cards, expired {}, closed {} unpaid orders", sent, expired, closed);
            }
        } catch (RuntimeException e) {
            log.warn("Gift card worker failed; it runs again shortly", e);
        }
    }
}
