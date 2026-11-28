/**
 * Marketing (plan Stage 8, step 8.5): SMS campaigns to people who agreed to hear from a business, counted links, and
 * stopping from the message itself. A person agrees by following the business (and keeping "news and offers" on) or
 * through the consent a business records for a client; either can be withdrawn by the person with the stop link in
 * every message. Messages are queued as notifications and sent by the communications module's dispatcher, which adds
 * the rules every marketing message follows (SMS only, 08:00 to 20:00 in the place's time, never to someone who stopped).
 *
 * <p>Public API: {@link com.beavent.backend.marketing.Marketing}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Marketing")
package com.beavent.backend.marketing;
