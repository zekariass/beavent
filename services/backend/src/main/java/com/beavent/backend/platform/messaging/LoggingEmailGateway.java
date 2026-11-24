package com.beavent.backend.platform.messaging;

import com.beavent.backend.platform.EmailGateway;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Development provider ({@code beavent.email.provider=log}): writes emails to the log and keeps the last one per
 * address for tests. Never enable in staging or production.
 */
@Component
@ConditionalOnProperty(name = "beavent.email.provider", havingValue = "log")
public class LoggingEmailGateway implements EmailGateway {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailGateway.class);
    private final Map<String, String> lastMessage = new ConcurrentHashMap<>();

    @Override
    public void send(String to, String subject, String text) {
        lastMessage.put(to.toLowerCase(java.util.Locale.ROOT), text);
        log.info("[EMAIL to {}] {} — {}", to, subject, text);
    }

    /** The body of the most recent email sent to {@code address}, or {@code null}. */
    public String lastMessageTo(String address) {
        return lastMessage.get(address.toLowerCase(java.util.Locale.ROOT));
    }
}
