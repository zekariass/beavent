package com.beavent.backend.crm.internal;

import static com.beavent.db.catalog.Tables.SERVICES;
import static com.beavent.db.crm.Tables.PATCH_TESTS;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.Ids;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.tenancy.OrgAccess;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Patch tests on file (plan step 2.6): the team records a test for a service that needs one; a pass is valid for the
 * service's validity days (180 when the service does not say). Online booking of that service needs a valid pass
 * (scheduling checks {@code crm.patch_tests} when the booking is confirmed); the team can always book.
 */
@Component
public class PatchTests {

    /** When a service needs a patch test but does not say for how long one counts. */
    public static final int DEFAULT_VALIDITY_DAYS = 180;

    private static final Set<String> RESULTS = Set.of("PASS", "FAIL", "PENDING");

    private final DSLContext dsl;
    private final TransactionTemplate transactions;
    private final Clients clients;

    PatchTests(DSLContext dsl, TransactionTemplate transactions, Clients clients) {
        this.dsl = dsl;
        this.transactions = transactions;
        this.clients = clients;
    }

    public UUID record(OrgAccess access, UUID customerId, ClientBodies.NewPatchTest input, Instant now) {
        access.require(Clients.MANAGE);
        if (input.serviceId() == null || !RESULTS.contains(input.result())) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Choose the service and the result.");
        }
        Instant performed = input.performedAt() == null ? now : input.performedAt();
        if (performed.isAfter(now.plus(Duration.ofHours(1)))) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "A patch test is recorded once it is done.");
        }
        if (input.notes() != null && input.notes().length() > 1000) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Keep the notes under 1,000 characters.");
        }
        return transactions.execute(status -> {
            UUID id = clients.active(customerId);
            var service = dsl.select(SERVICES.PATCH_TEST_VALIDITY_DAYS)
                    .from(SERVICES)
                    .where(SERVICES.ID.eq(input.serviceId()))
                    .fetchOptional()
                    .orElseThrow(() -> new ApiException(ProblemCode.REFERENCE_NOT_FOUND, "There is no such service."));
            int days = service.value1() == null ? DEFAULT_VALIDITY_DAYS : service.value1();
            UUID testId = Ids.newId();
            dsl.insertInto(PATCH_TESTS)
                    .set(PATCH_TESTS.ID, testId)
                    .set(PATCH_TESTS.ORG_ID, access.orgId())
                    .set(PATCH_TESTS.CUSTOMER_ID, id)
                    .set(PATCH_TESTS.SERVICE_ID, input.serviceId())
                    .set(PATCH_TESTS.PERFORMED_AT, performed.atOffset(ZoneOffset.UTC))
                    .set(PATCH_TESTS.RESULT, input.result())
                    .set(
                            PATCH_TESTS.VALID_UNTIL,
                            input.result().equals("PASS")
                                    ? performed.plus(Duration.ofDays(days)).atOffset(ZoneOffset.UTC)
                                    : null)
                    .set(
                            PATCH_TESTS.NOTES,
                            input.notes() == null || input.notes().isBlank()
                                    ? null
                                    : input.notes().strip())
                    .execute();
            return testId;
        });
    }

    /** Removes a test recorded by mistake. */
    public void delete(OrgAccess access, UUID customerId, UUID testId) {
        access.require(Clients.MANAGE);
        int deleted = transactions.execute(status -> dsl.deleteFrom(PATCH_TESTS)
                .where(PATCH_TESTS.ID.eq(testId), PATCH_TESTS.CUSTOMER_ID.eq(customerId))
                .execute());
        if (deleted == 0) {
            throw new ApiException(ProblemCode.NOT_FOUND, "There is no such patch test.");
        }
    }
}
