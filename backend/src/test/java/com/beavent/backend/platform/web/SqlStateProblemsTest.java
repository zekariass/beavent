package com.beavent.backend.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.beavent.backend.platform.ProblemCode;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ProblemDetail;

class SqlStateProblemsTest {

    private static DataIntegrityViolationException dbError(String sqlState, String table) {
        String raw = "SERROR\0C" + sqlState + "\0Mrejected\0" + (table == null ? "" : "t" + table + "\0");
        return new DataIntegrityViolationException("wrapped", new PSQLException(new ServerErrorMessage(raw)));
    }

    @Test
    void mapsSchemaInvariantsToClientCodes() {
        assertThat(SqlStateProblems.from(dbError("23P01", "schedule_reservations")))
                .isEqualTo(ProblemCode.SLOT_TAKEN);
        assertThat(SqlStateProblems.from(dbError("23514", "inventory_buckets"))).isEqualTo(ProblemCode.SOLD_OUT);
        assertThat(SqlStateProblems.from(dbError("23514", "order_lines"))).isEqualTo(ProblemCode.CONSTRAINT_VIOLATION);
        assertThat(SqlStateProblems.from(dbError("23505", "orders"))).isEqualTo(ProblemCode.CONFLICT);
        assertThat(SqlStateProblems.from(dbError("23503", "order_lines"))).isEqualTo(ProblemCode.REFERENCE_NOT_FOUND);
        assertThat(SqlStateProblems.from(dbError("23001", "entries"))).isEqualTo(ProblemCode.IMMUTABLE);
        assertThat(SqlStateProblems.from(dbError("42501", null))).isEqualTo(ProblemCode.FORBIDDEN);
        assertThat(SqlStateProblems.from(dbError("40P01", null))).isEqualTo(ProblemCode.CONCURRENT_UPDATE);
        assertThat(SqlStateProblems.from(dbError("XX000", null))).isEqualTo(ProblemCode.INTERNAL);
        assertThat(SqlStateProblems.from(new IllegalStateException())).isEqualTo(ProblemCode.INTERNAL);
    }

    @Test
    void problemsCarryCodeAndTypeButNoDatabaseDetails() {
        ProblemDetail problem = ProblemHandler.problem(ProblemCode.SOLD_OUT, ProblemCode.SOLD_OUT.defaultDetail());
        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getType()).hasToString("urn:beavent:problem:sold-out");
        assertThat(problem.getProperties()).containsEntry("code", "SOLD_OUT");
        assertThat(problem.getDetail()).doesNotContain("inventory_buckets");
    }
}
