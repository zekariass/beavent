package com.beavent.backend.scheduling.internal;

import static com.beavent.db.scheduling.Tables.APPOINTMENTS;

import com.beavent.backend.platform.Outbox;
import com.beavent.backend.platform.SystemDb;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Live calendar updates (plan step 2.5, R6): screens showing a location's calendar keep a server-sent-events stream
 * open; whenever an appointment or block there changes, every stream for that location gets a small "changed" event
 * and the screen reloads what it shows (refetch-on-notify: messages stay tiny and never carry personal data).
 *
 * <p>Each API instance listens on its own short-lived queue bound to the booking events of the outbox, so a change
 * made through one instance reaches screens connected to any other. Streams send a heartbeat every 25 seconds so
 * proxies keep them open; clients reconnect on their own (EventSource) or fall back to polling.
 */
@Component
public class CalendarStream {

    /** Streams are closed after this long; clients reconnect (it bounds what a forgotten tab holds open). */
    static final Duration LIFETIME = Duration.ofMinutes(30);

    private static final Logger log = LoggerFactory.getLogger(CalendarStream.class);

    private final Map<UUID, Set<SseEmitter>> byLocation = new ConcurrentHashMap<>();
    private final DSLContext system;
    private final JsonMapper json;

    CalendarStream(@SystemDb DSLContext system, JsonMapper json) {
        this.system = system;
        this.json = json;
    }

    /** A stream for one location's calendar. The caller checked the person may see it. */
    public SseEmitter subscribe(UUID locationId) {
        SseEmitter emitter = new SseEmitter(LIFETIME.toMillis());
        Set<SseEmitter> set = byLocation.computeIfAbsent(locationId, k -> new CopyOnWriteArraySet<>());
        set.add(emitter);
        Runnable remove = () -> set.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        send(emitter, "ready", Map.of("locationId", locationId));
        return emitter;
    }

    /** How many screens watch a location (for tests and metrics). */
    public int watchers(UUID locationId) {
        return byLocation.getOrDefault(locationId, Set.of()).size();
    }

    @RabbitListener(queues = "#{calendarStreamQueue.name}")
    void onEvent(Message message) {
        try {
            var body = json.readTree(message.getBody());
            UUID locationId = body.has("locationId")
                    ? UUID.fromString(body.get("locationId").asString())
                    : body.has("appointmentId")
                            ? system.select(APPOINTMENTS.LOCATION_ID)
                                    .from(APPOINTMENTS)
                                    .where(APPOINTMENTS.ID.eq(UUID.fromString(
                                            body.get("appointmentId").asString())))
                                    .fetchOne(APPOINTMENTS.LOCATION_ID)
                            : null;
            if (locationId != null) {
                changed(locationId, message.getMessageProperties().getReceivedRoutingKey());
            }
        } catch (RuntimeException e) {
            // Live updates are a convenience: a lost one is covered by the next change, a reload or polling.
            log.debug("Calendar stream event ignored: {}", e.getMessage());
        }
    }

    void changed(UUID locationId, String what) {
        for (SseEmitter emitter : byLocation.getOrDefault(locationId, Set.of())) {
            send(
                    emitter,
                    "changed",
                    Map.of(
                            "locationId",
                            locationId,
                            "what",
                            what,
                            "at",
                            Instant.now().toString()));
        }
    }

    @Scheduled(fixedDelay = 25_000)
    void heartbeat() {
        byLocation
                .values()
                .forEach(set -> set.forEach(emitter -> {
                    try {
                        emitter.send(SseEmitter.event().comment("keep-alive"));
                    } catch (IOException | IllegalStateException e) {
                        set.remove(emitter);
                    }
                }));
    }

    private void send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(json.writeValueAsString(data)));
        } catch (IOException | IllegalStateException e) {
            byLocation.values().forEach(set -> set.remove(emitter));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Topology {

        /** One queue per API instance, deleted when the instance stops. */
        @Bean
        Queue calendarStreamQueue() {
            return new AnonymousQueue();
        }

        @Bean
        Declarables calendarStreamBindings(Queue calendarStreamQueue) {
            TopicExchange events = new TopicExchange(Outbox.EXCHANGE);
            Binding appointments =
                    BindingBuilder.bind(calendarStreamQueue).to(events).with("appointment.#");
            Binding calendar =
                    BindingBuilder.bind(calendarStreamQueue).to(events).with("calendar.#");
            return new Declarables(appointments, calendar);
        }
    }
}
