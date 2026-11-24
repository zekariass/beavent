package com.beavent.backend.identity;

import java.util.Optional;
import java.util.UUID;

/**
 * Who a signed-in person is, for modules that keep their own record of them (a business's customer record when they
 * book). Only verified contact details are returned: a business must never receive an unproven phone or address.
 */
public interface People {

    /**
     * @param phone verified phone in E.164, or {@code null}
     * @param email verified address, or {@code null}
     * @param language preferred language code, or {@code null}
     */
    record Person(
            UUID userId,
            String firstName,
            String lastName,
            String displayName,
            String phone,
            String email,
            String language) {}

    Optional<Person> find(UUID userId);
}
