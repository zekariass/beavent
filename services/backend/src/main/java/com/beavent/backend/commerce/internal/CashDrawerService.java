package com.beavent.backend.commerce.internal;

import static com.beavent.db.commerce.Tables.CASH_DRAWERS;
import static com.beavent.db.commerce.Tables.CASH_DRAWER_SESSIONS;
import static com.beavent.db.commerce.Tables.CASH_MOVEMENTS;
import static com.beavent.db.tenant.Tables.LEGAL_ENTITIES;
import static com.beavent.db.tenant.Tables.LOCATIONS;

import com.beavent.backend.commerce.CashDrawers;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.Ids;
import com.beavent.backend.platform.ProblemCode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.springframework.stereotype.Service;

/** {@link CashDrawers}: one drawer per location, one open session at a time (the database holds the line). */
@Service
class CashDrawerService implements CashDrawers {

    private static final String NAME = "Front desk";
    private static final Set<String> MOVEMENTS = Set.of("PAY_IN", "PAY_OUT", "DROP");
    private static final String UNIQUE_VIOLATION = "23505";

    private final DSLContext dsl;

    CashDrawerService(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Session current(UUID orgId, UUID locationId) {
        var open = dsl.selectFrom(CASH_DRAWER_SESSIONS)
                .where(CASH_DRAWER_SESSIONS.ORG_ID.eq(orgId))
                .and(CASH_DRAWER_SESSIONS.STATUS.eq("OPEN"))
                .and(CASH_DRAWER_SESSIONS.DRAWER_ID.in(dsl.select(CASH_DRAWERS.ID)
                        .from(CASH_DRAWERS)
                        .where(CASH_DRAWERS.ORG_ID.eq(orgId), CASH_DRAWERS.LOCATION_ID.eq(locationId))))
                .fetchOne();
        return open == null ? null : session(open, true);
    }

    @Override
    public Session find(UUID orgId, UUID sessionId) {
        var s = sessionRow(orgId, sessionId);
        return session(s, true);
    }

    @Override
    public Session open(UUID orgId, UUID locationId, long openingFloatMinor, UUID userId) {
        if (openingFloatMinor < 0) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The float cannot be negative.");
        }
        UUID drawerId = drawer(orgId, locationId);
        UUID id = Ids.newId();
        try {
            dsl.insertInto(CASH_DRAWER_SESSIONS)
                    .set(CASH_DRAWER_SESSIONS.ID, id)
                    .set(CASH_DRAWER_SESSIONS.ORG_ID, orgId)
                    .set(CASH_DRAWER_SESSIONS.DRAWER_ID, drawerId)
                    .set(CASH_DRAWER_SESSIONS.OPENED_BY, userId)
                    .set(CASH_DRAWER_SESSIONS.OPENING_FLOAT_MINOR, openingFloatMinor)
                    .execute();
        } catch (DataAccessException e) {
            if (UNIQUE_VIOLATION.equals(e.sqlState())) {
                throw new ApiException(ProblemCode.CONFLICT, "The drawer is open already.");
            }
            throw e;
        }
        return session(sessionRow(orgId, id), true);
    }

    @Override
    public Session movement(UUID orgId, UUID sessionId, String kind, long amountMinor, String note, UUID userId) {
        if (!MOVEMENTS.contains(kind) || amountMinor <= 0) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Say what went in or out, and how much.");
        }
        var s = sessionRow(orgId, sessionId);
        if (!s.getStatus().equals("OPEN")) throw new ApiException(ProblemCode.CONFLICT, "The drawer is closed.");
        long expected = expected(s);
        long signed = kind.equals("PAY_IN") ? amountMinor : -amountMinor;
        if (expected + signed < 0) {
            throw new ApiException(ProblemCode.CONSTRAINT_VIOLATION, "There is not that much cash in the drawer.");
        }
        dsl.insertInto(CASH_MOVEMENTS)
                .set(CASH_MOVEMENTS.ORG_ID, orgId)
                .set(CASH_MOVEMENTS.SESSION_ID, sessionId)
                .set(CASH_MOVEMENTS.KIND, kind)
                .set(CASH_MOVEMENTS.AMOUNT_MINOR, signed)
                .set(CASH_MOVEMENTS.NOTE, note == null || note.isBlank() ? null : note.strip())
                .set(CASH_MOVEMENTS.ACTOR_USER_ID, userId)
                .execute();
        return session(sessionRow(orgId, sessionId), true);
    }

    @Override
    public Session close(UUID orgId, UUID sessionId, long countedMinor, UUID userId) {
        if (countedMinor < 0) throw new ApiException(ProblemCode.VALIDATION_FAILED, "The count cannot be negative.");
        var s = dsl.selectFrom(CASH_DRAWER_SESSIONS)
                .where(CASH_DRAWER_SESSIONS.ID.eq(sessionId))
                .and(CASH_DRAWER_SESSIONS.ORG_ID.eq(orgId))
                .forUpdate()
                .fetchOne();
        if (s == null) throw new ApiException(ProblemCode.NOT_FOUND, "There is no such drawer session.");
        if (!s.getStatus().equals("OPEN"))
            throw new ApiException(ProblemCode.CONFLICT, "The drawer is closed already.");
        dsl.update(CASH_DRAWER_SESSIONS)
                .set(CASH_DRAWER_SESSIONS.STATUS, "CLOSED")
                .set(CASH_DRAWER_SESSIONS.CLOSED_BY, userId)
                .set(CASH_DRAWER_SESSIONS.CLOSED_AT, Instant.now().atOffset(ZoneOffset.UTC))
                .set(CASH_DRAWER_SESSIONS.COUNTED_MINOR, countedMinor)
                .set(CASH_DRAWER_SESSIONS.EXPECTED_MINOR, expected(s))
                .where(CASH_DRAWER_SESSIONS.ID.eq(sessionId))
                .execute();
        return session(sessionRow(orgId, sessionId), true);
    }

    @Override
    public List<Session> history(UUID orgId, UUID locationId, int limit) {
        return dsl.selectFrom(CASH_DRAWER_SESSIONS)
                .where(CASH_DRAWER_SESSIONS.ORG_ID.eq(orgId))
                .and(CASH_DRAWER_SESSIONS.STATUS.ne("OPEN"))
                .and(CASH_DRAWER_SESSIONS.DRAWER_ID.in(dsl.select(CASH_DRAWERS.ID)
                        .from(CASH_DRAWERS)
                        .where(CASH_DRAWERS.ORG_ID.eq(orgId), CASH_DRAWERS.LOCATION_ID.eq(locationId))))
                .orderBy(CASH_DRAWER_SESSIONS.OPENED_AT.desc())
                .limit(limit)
                .fetch(s -> session(s, false));
    }

    @Override
    public void recordSale(UUID orgId, UUID sessionId, UUID attemptId, long amountMinor, UUID userId) {
        var s = sessionRow(orgId, sessionId);
        if (!s.getStatus().equals("OPEN")) throw new ApiException(ProblemCode.CONFLICT, "The drawer is closed.");
        dsl.insertInto(CASH_MOVEMENTS)
                .set(CASH_MOVEMENTS.ORG_ID, orgId)
                .set(CASH_MOVEMENTS.SESSION_ID, sessionId)
                .set(CASH_MOVEMENTS.KIND, "SALE")
                .set(CASH_MOVEMENTS.AMOUNT_MINOR, amountMinor)
                .set(CASH_MOVEMENTS.PAYMENT_ATTEMPT_ID, attemptId)
                .set(CASH_MOVEMENTS.ACTOR_USER_ID, userId)
                .execute();
    }

    // --- internals ---------------------------------------------------------------------------------------------------

    /** The location's drawer, made on first use in the currency of its legal entity. */
    private UUID drawer(UUID orgId, UUID locationId) {
        var location = dsl.select(LEGAL_ENTITIES.FUNCTIONAL_CURRENCY)
                .from(LOCATIONS)
                .join(LEGAL_ENTITIES)
                .on(LEGAL_ENTITIES.ID.eq(LOCATIONS.LEGAL_ENTITY_ID))
                .where(LOCATIONS.ID.eq(locationId), LOCATIONS.ORG_ID.eq(orgId))
                .fetchOne();
        if (location == null) throw new ApiException(ProblemCode.NOT_FOUND, "There is no such location.");
        dsl.insertInto(CASH_DRAWERS)
                .set(CASH_DRAWERS.ORG_ID, orgId)
                .set(CASH_DRAWERS.LOCATION_ID, locationId)
                .set(CASH_DRAWERS.NAME, NAME)
                .set(CASH_DRAWERS.CURRENCY_CODE, location.value1())
                .onConflict(CASH_DRAWERS.ORG_ID, CASH_DRAWERS.LOCATION_ID, CASH_DRAWERS.NAME)
                .doNothing()
                .execute();
        return dsl.select(CASH_DRAWERS.ID)
                .from(CASH_DRAWERS)
                .where(
                        CASH_DRAWERS.ORG_ID.eq(orgId),
                        CASH_DRAWERS.LOCATION_ID.eq(locationId),
                        CASH_DRAWERS.NAME.eq(NAME))
                .fetchSingle(CASH_DRAWERS.ID);
    }

    private com.beavent.db.commerce.tables.records.CashDrawerSessionsRecord sessionRow(UUID orgId, UUID sessionId) {
        var s = dsl.selectFrom(CASH_DRAWER_SESSIONS)
                .where(CASH_DRAWER_SESSIONS.ID.eq(sessionId))
                .and(CASH_DRAWER_SESSIONS.ORG_ID.eq(orgId))
                .fetchOne();
        if (s == null) throw new ApiException(ProblemCode.NOT_FOUND, "There is no such drawer session.");
        return s;
    }

    /** The float and every movement of the session. */
    private long expected(com.beavent.db.commerce.tables.records.CashDrawerSessionsRecord s) {
        Long sum = dsl.select(org.jooq.impl.DSL.coalesce(org.jooq.impl.DSL.sum(CASH_MOVEMENTS.AMOUNT_MINOR), 0))
                .from(CASH_MOVEMENTS)
                .where(CASH_MOVEMENTS.SESSION_ID.eq(s.getId()))
                .fetchOne(0, Long.class);
        return s.getOpeningFloatMinor() + (sum == null ? 0 : sum);
    }

    private Session session(com.beavent.db.commerce.tables.records.CashDrawerSessionsRecord s, boolean withMovements) {
        var drawer = dsl.selectFrom(CASH_DRAWERS)
                .where(CASH_DRAWERS.ID.eq(s.getDrawerId()))
                .fetchSingle();
        List<Movement> movements = dsl.selectFrom(CASH_MOVEMENTS)
                .where(CASH_MOVEMENTS.SESSION_ID.eq(s.getId()))
                .orderBy(CASH_MOVEMENTS.OCCURRED_AT.desc(), CASH_MOVEMENTS.ID.desc())
                .fetch(m -> new Movement(
                        m.getId(),
                        m.getKind(),
                        m.getAmountMinor(),
                        m.getNote(),
                        m.getOccurredAt().toInstant(),
                        m.getActorUserId()));
        long sales = movements.stream()
                .filter(m -> m.kind().equals("SALE"))
                .mapToLong(Movement::amountMinor)
                .sum();
        long in = movements.stream()
                .filter(m -> m.kind().equals("PAY_IN"))
                .mapToLong(Movement::amountMinor)
                .sum();
        long out = -movements.stream()
                .filter(m -> m.kind().equals("PAY_OUT") || m.kind().equals("DROP"))
                .mapToLong(Movement::amountMinor)
                .sum();
        long expected = s.getOpeningFloatMinor()
                + movements.stream().mapToLong(Movement::amountMinor).sum();
        Long counted = s.getCountedMinor();
        return new Session(
                s.getId(),
                drawer.getLocationId(),
                drawer.getName(),
                drawer.getCurrencyCode().trim(),
                s.getStatus(),
                s.getOpenedAt().toInstant(),
                s.getOpenedBy(),
                s.getOpeningFloatMinor(),
                sales,
                in,
                out,
                s.getStatus().equals("OPEN") ? expected : s.getExpectedMinor(),
                counted,
                counted == null ? null : counted - s.getExpectedMinor(),
                s.getClosedAt() == null ? null : s.getClosedAt().toInstant(),
                withMovements ? movements : List.of());
    }
}
