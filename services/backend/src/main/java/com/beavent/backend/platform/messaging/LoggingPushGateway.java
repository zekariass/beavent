package com.beavent.backend.platform.messaging;

import com.beavent.backend.platform.PushGateway;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Development provider ({@code beavent.push.provider=log}): writes pushes to the log instead of sending them and keeps
 * them per token for tests. A token that starts with {@code gone:} is treated as a device that can no longer receive
 * pushes. Never enable in staging or production.
 */
@Component
@ConditionalOnProperty(name = "beavent.push.provider", havingValue = "log")
public class LoggingPushGateway implements PushGateway {

    private static final Logger log = LoggerFactory.getLogger(LoggingPushGateway.class);
    private final Map<String, List<Message>> all = new ConcurrentHashMap<>();

    @Override
    public Map<String, Outcome> send(List<String> tokens, Message message) {
        Map<String, Outcome> result = new HashMap<>();
        for (String token : tokens) {
            if (token.startsWith("gone:")) {
                result.put(token, Outcome.DEVICE_GONE);
                continue;
            }
            all.computeIfAbsent(token, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                    .add(message);
            log.info("[PUSH to {}] {} — {} {}", token, message.title(), message.body(), message.data());
            result.put(token, Outcome.SENT);
        }
        return result;
    }

    /** Every push sent to {@code token}, oldest first. */
    public List<Message> messagesTo(String token) {
        return List.copyOf(all.getOrDefault(token, List.of()));
    }
}
