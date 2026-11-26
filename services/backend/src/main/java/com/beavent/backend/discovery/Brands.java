package com.beavent.backend.discovery;

import java.util.Map;
import java.util.Optional;

/** The public face of a business as a whole, for its brand page (plan step 8.2): who it is, how many follow it. */
public interface Brands {

    /**
     * @param places how many of its places are listed on the marketplace
     * @param followers how many people follow it
     */
    record Brand(
            String slug,
            String name,
            Map<String, String> description,
            Map<String, String> category,
            String logoUrl,
            Map<String, String> logoVariants,
            int places,
            int followers) {}

    /** A business that is active or still onboarding (the venue page's rule); anything else is absent. */
    Optional<Brand> brand(String businessSlug);
}
