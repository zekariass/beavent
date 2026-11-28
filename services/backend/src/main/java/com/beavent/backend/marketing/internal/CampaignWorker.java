package com.beavent.backend.marketing.internal;

import com.beavent.backend.marketing.Marketing;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Starts campaigns whose time has come: queues their messages for the dispatcher. */
@Component
class CampaignWorker {

    private static final Logger log = LoggerFactory.getLogger(CampaignWorker.class);
    private final Marketing marketing;

    CampaignWorker(Marketing marketing) {
        this.marketing = marketing;
    }

    @Scheduled(fixedDelayString = "${beavent.marketing.worker-interval-ms:20000}")
    void run() {
        try {
            marketing.sendDue(Instant.now());
        } catch (RuntimeException e) {
            log.warn("Campaign worker failed; it runs again shortly", e);
        }
    }
}
