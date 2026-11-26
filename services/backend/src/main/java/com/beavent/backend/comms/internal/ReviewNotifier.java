package com.beavent.backend.comms.internal;

import static com.beavent.db.comms.Tables.NOTIFICATIONS;

import com.beavent.backend.platform.Ids;
import com.beavent.backend.platform.Outbox;
import com.beavent.backend.platform.ProcessedMessages;
import com.beavent.backend.platform.SystemDb;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tells the people concerned about reviews and about being on the marketplace (plan 8.4): the business when a review
 * arrives and when staff decide about one it reported or about its listing, the author when staff take a review down
 * or put it back. Each notification has a dedupe key, so a redelivered event adds nothing.
 */
@Component
class ReviewNotifier {

    static final String QUEUE = "beavent.comms.discovery";
    private static final String CONSUMER = "comms.discovery";

    @Configuration(proxyBeanMethods = false)
    static class Queues {

        @Bean
        Queue commsDiscoveryQueue() {
            return QueueBuilder.durable(QUEUE)
                    .deadLetterExchange(Outbox.DEAD_LETTER_EXCHANGE)
                    .build();
        }

        @Bean
        Binding commsReviewPublishedBinding(Queue commsDiscoveryQueue) {
            return BindingBuilder.bind(commsDiscoveryQueue)
                    .to(new TopicExchange(Outbox.EXCHANGE))
                    .with("review.published");
        }

        @Bean
        Binding commsReviewModeratedBinding(Queue commsDiscoveryQueue) {
            return BindingBuilder.bind(commsDiscoveryQueue)
                    .to(new TopicExchange(Outbox.EXCHANGE))
                    .with("review.moderated");
        }

        @Bean
        Binding commsMarketplaceBinding(Queue commsDiscoveryQueue) {
            return BindingBuilder.bind(commsDiscoveryQueue)
                    .to(new TopicExchange(Outbox.EXCHANGE))
                    .with("org.marketplace_changed");
        }
    }

    private final DSLContext system;
    private final ProcessedMessages processedMessages;
    private final JsonMapper json;
    private final String webUrl;

    ReviewNotifier(
            @SystemDb DSLContext system,
            ProcessedMessages processedMessages,
            JsonMapper json,
            @Value("${beavent.web.public-url}") String webUrl) {
        this.system = system;
        this.processedMessages = processedMessages;
        this.json = json;
        this.webUrl = webUrl.replaceAll("/+$", "");
    }

    @RabbitListener(queues = QUEUE)
    void onEvent(Message message) {
        UUID messageId = UUID.fromString(message.getMessageProperties().getMessageId());
        String type = message.getMessageProperties().getReceivedRoutingKey();
        UUID orgId = UUID.fromString(message.getMessageProperties().<String>getHeader("org_id"));
        JsonNode body = json.readTree(message.getBody());
        processedMessages.runOnce(CONSUMER, messageId, () -> handle(type, orgId, body, Instant.now()));
    }

    void handle(String type, UUID orgId, JsonNode body, Instant now) {
        switch (type) {
            case "review.published" -> {
                String reviewId = body.get("reviewId").asString();
                Map<String, Object> data = base(orgId, body.get("locationId").asString());
                data.put("rating", body.get("rating").asInt());
                data.put("web", webUrl);
                data.put("orgId", orgId.toString());
                for (UUID user : peopleWith(orgId, "reviews.respond")) {
                    add(
                            orgId,
                            user,
                            Notifier.REVIEW_RECEIVED,
                            "OPERATIONAL",
                            data,
                            Notifier.REVIEW_RECEIVED + ":" + reviewId + ":" + user,
                            now);
                }
            }
            case "review.moderated" -> {
                String reviewId = body.get("reviewId").asString();
                String decision = body.get("decision").asString();
                Map<String, Object> data = base(orgId, body.get("locationId").asString());
                data.put("decision", decision);
                String reason = body.has("reason") ? body.get("reason").asString() : "";
                String key = reviewId + ":" + decision + ":" + body.get("at").asString();
                // The author learns a review was taken down, or is back.
                if (body.has("authorUserId")) {
                    UUID author = UUID.fromString(body.get("authorUserId").asString());
                    if (decision.equals("HIDE")) {
                        Map<String, Object> withReason = new LinkedHashMap<>(data);
                        withReason.put("reason", reason);
                        add(
                                orgId,
                                author,
                                Notifier.REVIEW_REMOVED,
                                "TRANSACTIONAL",
                                withReason,
                                Notifier.REVIEW_REMOVED + ":" + key,
                                now);
                    } else if (decision.equals("RESTORE")) {
                        add(
                                orgId,
                                author,
                                Notifier.REVIEW_RESTORED,
                                "TRANSACTIONAL",
                                data,
                                Notifier.REVIEW_RESTORED + ":" + key,
                                now);
                    }
                }
                // The business that reported it learns what staff decided.
                if (body.has("reported") && body.get("reported").asBoolean() && !decision.equals("RESTORE")) {
                    for (UUID user : peopleWith(orgId, "reviews.respond")) {
                        add(
                                orgId,
                                user,
                                Notifier.REVIEW_REPORT_OUTCOME,
                                "OPERATIONAL",
                                data,
                                Notifier.REVIEW_REPORT_OUTCOME + ":" + key + ":" + user,
                                now);
                    }
                }
            }
            case "org.marketplace_changed" -> {
                String status = body.get("status").asString();
                String note = body.has("note") ? body.get("note").asString() : "";
                // Only staff decisions are news: listed, taken off, or a request sent back with a reason.
                // (The business's own request and withdrawal publish the same event.)
                boolean decision = status.equals("LISTED")
                        || status.equals("DELISTED")
                        || (status.equals("HIDDEN") && !note.isBlank());
                if (!decision) {
                    return;
                }
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("business", businessName(orgId));
                data.put("place", "");
                data.put("status", status);
                data.put("reason", note);
                String key = Notifier.MARKETPLACE_CHANGED + ":" + orgId + ":" + status + ":"
                        + body.get("at").asString();
                for (UUID user : peopleWith(orgId, "org.settings.manage")) {
                    add(orgId, user, Notifier.MARKETPLACE_CHANGED, "OPERATIONAL", data, key + ":" + user, now);
                }
            }
            default -> {
                // not for us
            }
        }
    }

    private String businessName(UUID orgId) {
        return system.resultQuery("SELECT display_name FROM tenant.organizations WHERE id = ?", orgId)
                .fetchOne(0, String.class);
    }

    private Map<String, Object> base(UUID orgId, String locationId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("business", businessName(orgId));
        data.put(
                "place",
                system.resultQuery("SELECT name FROM tenant.locations WHERE id = ?", UUID.fromString(locationId))
                        .fetchOne(0, String.class));
        return data;
    }

    /** The business's owners and the active members whose role holds {@code permission}. */
    private List<UUID> peopleWith(UUID orgId, String permission) {
        return system.resultQuery("""
                        SELECT DISTINCT m.user_id
                          FROM tenant.members m
                          LEFT JOIN tenant.role_permissions rp ON rp.role_id = m.role_id AND rp.permission_code = ?
                         WHERE m.org_id = ? AND m.status = 'ACTIVE' AND (m.is_owner OR rp.permission_code IS NOT NULL)
                        """, permission, orgId).fetch(0, UUID.class);
    }

    private void add(
            UUID orgId,
            UUID user,
            String code,
            String category,
            Map<String, Object> data,
            String dedupeKey,
            Instant now) {
        system.insertInto(NOTIFICATIONS)
                .set(NOTIFICATIONS.ID, Ids.newId())
                .set(NOTIFICATIONS.ORG_ID, orgId)
                .set(NOTIFICATIONS.RECIPIENT_USER_ID, user)
                .set(NOTIFICATIONS.CATEGORY, category)
                .set(NOTIFICATIONS.TEMPLATE_CODE, code)
                .set(NOTIFICATIONS.DATA, JSONB.valueOf(json.writeValueAsString(data)))
                .set(NOTIFICATIONS.DEDUPE_KEY, dedupeKey)
                .set(NOTIFICATIONS.SEND_AFTER, now.atOffset(ZoneOffset.UTC))
                .onConflictDoNothing()
                .execute();
    }
}
