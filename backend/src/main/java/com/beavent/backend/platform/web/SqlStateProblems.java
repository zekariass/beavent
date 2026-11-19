package com.beavent.backend.platform.web;

import com.beavent.backend.platform.ProblemCode;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * Maps PostgreSQL errors raised by the schema's invariants (docs/05 §6) to client-facing problem codes. Raw database
 * messages are never returned to clients.
 */
final class SqlStateProblems {

    private SqlStateProblems() {}

    static ProblemCode from(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException psql) {
                ServerErrorMessage message = psql.getServerErrorMessage();
                String table = message == null ? null : message.getTable();
                return from(psql.getSQLState(), table);
            }
        }
        return ProblemCode.INTERNAL;
    }

    static ProblemCode from(String sqlState, String table) {
        if (sqlState == null) {
            return ProblemCode.INTERNAL;
        }
        return switch (sqlState) {
            case "23P01" -> ProblemCode.SLOT_TAKEN; // exclusion constraint: staff/resource double booking
            case "23514" ->
                "inventory_buckets".equals(table)
                        ? ProblemCode.SOLD_OUT // bucket capacity CHECK
                        : ProblemCode.CONSTRAINT_VIOLATION;
            case "23505" -> ProblemCode.CONFLICT; // unique violation
            case "23503" -> ProblemCode.REFERENCE_NOT_FOUND; // foreign key (incl. cross-tenant references)
            case "23502" -> ProblemCode.VALIDATION_FAILED; // not null
            case "23001" -> ProblemCode.IMMUTABLE; // append-only tables (platform.reject_mutation)
            case "42501" -> ProblemCode.FORBIDDEN; // privileges or row-level security
            case "40001", "40P01" -> ProblemCode.CONCURRENT_UPDATE; // serialization failure / deadlock
            default -> ProblemCode.INTERNAL;
        };
    }
}
