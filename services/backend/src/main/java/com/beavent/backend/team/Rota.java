package com.beavent.backend.team;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Working hours per person and local day: one-off shifts replace the regular pattern for that day; approved time off
 * is cut out. Location closures and public holidays are applied by availability (they are not the team's rota).
 * Call inside a transaction with the organization's context.
 */
public interface Rota {

    record Interval(UUID locationId, Instant startsAt, Instant endsAt) {}

    /** @param source {@code PATTERN} or {@code SHIFTS} */
    record Day(UUID staffId, LocalDate date, String source, List<Interval> intervals) {}

    /**
     * @param staffIds people to include; empty = every active, bookable-or-not team member
     * @param locationId only working time at this location, or {@code null}
     */
    List<Day> schedule(Collection<UUID> staffIds, UUID locationId, LocalDate from, LocalDate to);
}
