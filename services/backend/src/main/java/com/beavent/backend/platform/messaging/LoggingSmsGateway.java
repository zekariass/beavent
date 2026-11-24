package com.beavent.backend.platform.messaging;

import com.beavent.backend.platform.SmsGateway;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Development provider ({@code beavent.sms.provider=log}): writes messages to the log instead of sending them and
 * keeps the last message per number for tests. Never enable in staging or production.
 */
@Component
@ConditionalOnProperty(name = "beavent.sms.provider", havingValue = "log")
public class LoggingSmsGateway implements SmsGateway {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsGateway.class);
    private final Map<String, String> lastMessage = new ConcurrentHashMap<>();
    private final Map<String, java.util.List<String>> all = new ConcurrentHashMap<>();

    @Override
    public void send(String to, Channel channel, String text) {
        lastMessage.put(to, text);
        all.computeIfAbsent(to, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(text);
        log.info("[{} to {}] {}", channel, to, text);
    }

    /** The most recent message sent to {@code phone}, or {@code null}. */
    public String lastMessageTo(String phone) {
        return lastMessage.get(phone);
    }

    /** Every message sent to {@code phone}, oldest first. */
    public java.util.List<String> messagesTo(String phone) {
        return java.util.List.copyOf(all.getOrDefault(phone, java.util.List.of()));
    }
}
