package com.beavent.backend.crm;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Which forms each appointment's client must fill in before the visit, and whether they have (plan step 2.6). Used
 * by the calendar; call it inside the business's context and transaction.
 */
public interface AppointmentForms {

    /**
     * @param title per language
     * @param required the business asks for it before the visit (otherwise it is offered)
     * @param done answered for this visit (or recently enough, or once, as the form says)
     */
    record Due(UUID formId, Map<String, String> title, String purpose, boolean required, boolean done) {}

    /** Forms per appointment id; appointments without forms are left out. */
    Map<UUID, List<Due>> due(Collection<UUID> appointmentIds);
}
