package com.beavent.backend.marketing;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SMS campaigns and counted links for a business (plan step 8.5). Everything here is for one organization; the caller
 * has checked {@code marketing.manage}.
 */
public interface Marketing {

    /** Largest audience one campaign is queued for. */
    int MAX_RECIPIENTS = 5000;

    /** Campaigns a business may start (send or schedule) per day. */
    int MAX_CAMPAIGNS_PER_DAY = 3;

    int MAX_TEXT = 480;

    enum Audience {
        /** People who follow the business and keep "news and offers" on. */
        FOLLOWERS,
        /** Clients with a phone number and a recorded agreement to hear from the business. */
        CLIENTS,
        /** Those clients who completed a visit within {@code days}. */
        PAST_CLIENTS,
        /** Those clients whose last completed visit was longer than {@code days} ago. */
        LAPSED_CLIENTS
    }

    /** How many people a campaign to this audience would reach, and how many were left out. */
    record AudienceCount(Audience audience, int reachable, int suppressed) {}

    /**
     * @param texts per language ({@code am}, {@code en}); a person gets their language's text, or the other one
     * @param days see {@link Audience}; default 365 for past clients, 90 for lapsed ones
     * @param locationId the place the link opens; {@code null} = the first active location
     */
    record Draft(String name, Audience audience, Integer days, UUID locationId, Map<String, String> texts) {}

    /** What became of a campaign's messages. {@code suppressed}: not sent because the person stopped or has no phone. */
    record Stats(int recipients, int sent, int failed, int suppressed, int clicks) {}

    record Campaign(
            UUID id,
            String name,
            Audience audience,
            Integer days,
            UUID locationId,
            Map<String, String> texts,
            String status,
            Instant scheduledAt,
            Instant sentAt,
            Stats stats,
            Instant createdAt) {}

    /** The message as a person will read it: the text, the link and the stop line, and how many SMS it takes. */
    record Preview(String language, String text, int characters, int segments) {}

    record TrackingLink(
            UUID id,
            String code,
            String url,
            String name,
            UUID locationId,
            int clicks,
            int clicksLast7Days,
            Instant createdAt) {}

    /** Where a link leads: a path on the web platform and the campaign parameters to add. */
    record Target(String path, Map<String, String> params) {}

    record Consent(boolean granted, Instant at, String source) {}

    // --- audiences --------------------------------------------------------------------------------------------

    AudienceCount audience(UUID orgId, Audience audience, Integer days);

    // --- campaigns --------------------------------------------------------------------------------------------

    List<Campaign> campaigns(UUID orgId);

    Optional<Campaign> campaign(UUID orgId, UUID campaignId);

    /** @throws com.beavent.backend.platform.ApiException {@code VALIDATION_FAILED} for a text that is too long or missing */
    Campaign create(UUID orgId, UUID userId, Draft draft);

    /** Changes a campaign that has not been sent; {@code CONFLICT} otherwise. */
    Campaign update(UUID orgId, UUID campaignId, Draft draft);

    /** Deletes a draft, or a cancelled campaign nobody was sent. */
    void delete(UUID orgId, UUID campaignId);

    /** How the message reads in each language the campaign has text for. */
    List<Preview> preview(UUID orgId, UUID campaignId);

    /** Sends the message to the caller's own verified phone, with the link and stop line, to see how it reads. */
    Preview testSend(UUID orgId, UUID campaignId, UUID userId);

    /**
     * Queues the campaign to be sent at {@code at} ({@code null} = now). Messages go between 08:00 and 20:00 in the
     * place's time. {@code CONFLICT} when it was already started, {@code CONSTRAINT_VIOLATION} past the daily limit or
     * with nobody to send to.
     */
    Campaign schedule(UUID orgId, UUID campaignId, UUID userId, Instant at, Instant now);

    /** Cancels a scheduled campaign; messages not yet sent are not sent. */
    Campaign cancel(UUID orgId, UUID campaignId, UUID userId);

    /** Queues the messages of campaigns that are due; returns how many campaigns were started. */
    int sendDue(Instant now);

    // --- tracking links ---------------------------------------------------------------------------------------

    List<TrackingLink> links(UUID orgId);

    TrackingLink createLink(UUID orgId, UUID userId, String name, UUID locationId);

    void deleteLink(UUID orgId, UUID linkId);

    /** Counts a click on a link or on a campaign message's link and says where it leads; empty for an unknown code. */
    Optional<Target> click(String code, Instant now);

    // --- agreement and stopping -------------------------------------------------------------------------------

    /** The client's current agreement to hear from the business by SMS. */
    Consent consent(UUID orgId, UUID customerId);

    /** Records that the business was told the client agrees (or no longer agrees) to SMS marketing. */
    Consent recordConsent(UUID orgId, UUID customerId, UUID userId, boolean granted, String note);

    /** The business a stop link belongs to; empty for an unknown token. */
    Optional<String> stopLink(String token);

    /** The person stops hearing from the business: this and every queued message, followed or not. */
    boolean stop(String token, Instant now);
}
