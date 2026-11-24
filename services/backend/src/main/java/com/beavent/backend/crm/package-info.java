/**
 * Clients (plan step 2.6, P-13): the business's record of each person it serves — contact details, notes with alerts
 * and allergies, tags, visit history, merging duplicates and importing a list. One record per (business, person),
 * linked to a Beavent account when known. Runs on the application role under row-level security.
 *
 * <p>No public Java API: scheduling reads the client tables it needs (name, phone, alerts) directly, and this module
 * reads appointments for the history, so neither depends on the other's code.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Clients")
package com.beavent.backend.crm;
