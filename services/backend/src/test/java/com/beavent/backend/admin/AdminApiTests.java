package com.beavent.backend.admin;

import static com.beavent.db.iam.Tables.USERS;
import static com.beavent.db.iam.Tables.USER_STAFF_ROLES;
import static com.beavent.db.platform.Tables.FEATURE_FLAGS;
import static com.beavent.db.platform.Tables.FILES;
import static com.beavent.db.ref.Tables.CATEGORIES;
import static com.beavent.db.ref.Tables.MARKETS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.beavent.backend.ApiTestSupport;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Admin console API (plan step 0.10): staff-only, multi-factor, market-scoped, every change audited. */
class AdminApiTests extends ApiTestSupport {

    // --- helpers ---------------------------------------------------------------------------------------------

    void grant(Person person, String roleCode, String marketCode) {
        UUID userId = system.select(USERS.ID)
                .from(USERS)
                .where(USERS.PHONE_E164.eq(person.phone()))
                .fetchSingle(USERS.ID);
        UUID market = marketCode == null
                ? null
                : system.select(MARKETS.ID)
                        .from(MARKETS)
                        .where(MARKETS.CODE.eq(marketCode))
                        .fetchSingle(MARKETS.ID);
        system.insertInto(USER_STAFF_ROLES)
                .set(USER_STAFF_ROLES.USER_ID, userId)
                .set(USER_STAFF_ROLES.ROLE_CODE, roleCode)
                .set(USER_STAFF_ROLES.MARKET_ID, market)
                .execute();
    }

    /** A staff member signed in to the admin console with a phone code. */
    Person staff(String roleCode, String marketCode) throws Exception {
        Person person = newPerson();
        grant(person, roleCode, marketCode);
        Call signedIn = signInWithOtp(person.phone(), "ADMIN_CONSOLE");
        assertThat(signedIn.status()).as(String.valueOf(signedIn.body())).isEqualTo(200);
        return new Person(person.phone(), signedIn.text("accessToken"));
    }

    String staffToken(String roleCode, String marketCode) throws Exception {
        return staff(roleCode, marketCode).token();
    }

    record Org(UUID id, Person owner, UUID caseId, UUID documentId) {}

    /** An organization that submitted business verification with one (processed) document. */
    Org orgAwaitingVerification(String name) throws Exception {
        Person owner = newPerson();
        UUID category = system.select(CATEGORIES.ID)
                .from(CATEGORIES)
                .where(CATEGORIES.KIND.eq("BUSINESS"), CATEGORIES.CODE.eq("BARBERSHOP"))
                .fetchSingle(CATEGORIES.ID);
        UUID orgId = UUID.fromString(call(
                        post("/v1/orgs"),
                        Map.of(
                                "displayName",
                                name,
                                "kind",
                                "BUSINESS",
                                "primaryCategoryId",
                                category,
                                "defaultLanguage",
                                "am"),
                        owner.token())
                .text("id"));
        String entity = call(
                        post("/v1/orgs/" + orgId + "/legal-entities"),
                        Map.of(
                                "countryCode",
                                "ET",
                                "entityType",
                                "SOLE_PROPRIETOR",
                                "registeredName",
                                name,
                                "taxRegime",
                                "TOT"),
                        owner.token())
                .text("id");
        UUID fileId = UUID.randomUUID();
        system.insertInto(FILES)
                .set(FILES.ID, fileId)
                .set(FILES.ORG_ID, orgId)
                .set(FILES.PURPOSE, "KYC")
                .set(FILES.VISIBILITY, "SENSITIVE")
                .set(FILES.BUCKET, "beavent-private")
                .set(FILES.STORAGE_KEY, "test/" + fileId)
                .set(FILES.CONTENT_TYPE, "application/pdf")
                .set(FILES.SIZE_BYTES, 1000L)
                .set(FILES.UPLOADED_AT, OffsetDateTime.now())
                .set(FILES.SCAN_STATUS, "CLEAN")
                .execute();
        Call submitted = call(
                post("/v1/orgs/" + orgId + "/verification-cases"),
                Map.of(
                        "subject",
                        "ORGANIZATION_KYB",
                        "legalEntityId",
                        entity,
                        "documents",
                        List.of(Map.of("docType", "TRADE_LICENSE", "fileId", fileId))),
                owner.token());
        assertThat(submitted.status()).isEqualTo(201);
        return new Org(
                orgId,
                owner,
                UUID.fromString(submitted.text("id")),
                UUID.fromString(
                        submitted.body().get("documents").get(0).get("id").asString()));
    }

    // --- tests -----------------------------------------------------------------------------------------------

    @Test
    void onlyStaffGetInAndTheirSessionsAreShort() throws Exception {
        Person outsider = newPerson();
        assertThat(signInWithOtp(outsider.phone(), "ADMIN_CONSOLE").status()).isEqualTo(403);
        assertThat(call(get("/v1/admin/me"), null, outsider.token()).status())
                .as("consumer token")
                .isEqualTo(403);
        assertThat(call(get("/v1/admin/me"), null, null).status()).isEqualTo(401);

        Person staff = newPerson();
        grant(staff, "SUPPORT_AGENT", null);
        Call signedIn = signInWithOtp(staff.phone(), "ADMIN_CONSOLE");
        assertThat(signedIn.status()).isEqualTo(200);
        assertThat(java.time.Duration.between(
                        java.time.Instant.now(), java.time.Instant.parse(signedIn.text("refreshTokenExpiresAt"))))
                .as("admin sessions last 12 hours")
                .isLessThanOrEqualTo(java.time.Duration.ofHours(12));
        String token = signedIn.text("accessToken");
        Call me = call(get("/v1/admin/me"), null, token);
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.body().get("roles").get(0).get("roleCode").asString()).isEqualTo("SUPPORT_AGENT");
        assertThat(me.body().get("permissions").toString())
                .contains("admin.support")
                .doesNotContain("admin.kyb.decide");
        assertThat(call(get("/v1/admin/verification-cases"), null, token).status())
                .isEqualTo(403);
    }

    @Test
    void operatorApprovesABusinessVerificationAndItAppearsInTheAuditLog() throws Exception {
        Org org = orgAwaitingVerification("Kappa Barbers");
        Person analystPerson = staff("RISK_ANALYST", "ET");
        String analyst = analystPerson.token();

        JsonNode queue = call(get("/v1/admin/verification-cases?limit=100"), null, analyst)
                .body()
                .get("items");
        assertThat(queue.toString()).contains(org.caseId().toString());
        Call detail = call(get("/v1/admin/verification-cases/" + org.caseId()), null, analyst);
        assertThat(detail.body().get("verificationCase").get("orgName").asString())
                .isEqualTo("Kappa Barbers");
        assertThat(detail.body().get("documents").size()).isEqualTo(1);
        String documentPath =
                "/v1/admin/verification-cases/" + org.caseId() + "/documents/" + org.documentId() + "/access-url";
        Call withoutCode = call(post(documentPath), null, analyst);
        assertThat(withoutCode.text("code")).as("documents need a fresh code").isEqualTo("STEP_UP_REQUIRED");
        String confirmed = stepUp(analystPerson);
        Call document = call(post(documentPath), null, confirmed);
        assertThat(document.status()).isEqualTo(200);
        assertThat(document.text("url")).contains("X-Amz-Signature");

        assertThat(callInvalid(
                                post("/v1/admin/verification-cases/" + org.caseId() + "/decision"),
                                Map.of("decision", "APPROVE"),
                                confirmed)
                        .status())
                .as("a reason is required")
                .isEqualTo(400);
        assertThat(call(
                                post("/v1/admin/verification-cases/" + org.caseId() + "/decision"),
                                Map.of("decision", "APPROVE", "reason", "Trade licence matches the registry"),
                                analyst)
                        .text("code"))
                .as("decisions need a fresh code")
                .isEqualTo("STEP_UP_REQUIRED");
        Call approved = call(
                post("/v1/admin/verification-cases/" + org.caseId() + "/decision"),
                Map.of("decision", "APPROVE", "reason", "Trade licence matches the registry"),
                confirmed);
        assertThat(approved.status()).isEqualTo(200);
        assertThat(approved.body().get("verificationCase").get("status").asString())
                .isEqualTo("APPROVED");
        assertThat(approved.body().get("legalEntity").get("verificationStatus").asString())
                .isEqualTo("VERIFIED");
        assertThat(call(get("/v1/orgs/" + org.id()), null, org.owner().token()).text("verificationStatus"))
                .isEqualTo("VERIFIED");
        assertThat(call(
                                post("/v1/admin/verification-cases/" + org.caseId() + "/decision"),
                                Map.of("decision", "REJECT", "reason", "Changed my mind"),
                                confirmed)
                        .status())
                .isEqualTo(409);

        JsonNode audit = call(get("/v1/admin/audit-log?orgId=" + org.id()), null, analyst)
                .body()
                .get("items");
        JsonNode decision = null;
        for (JsonNode entry : audit) {
            if (entry.get("action").asString().equals("verification_case.approve")) {
                decision = entry;
            }
        }
        assertThat(decision).as("decision in the audit log").isNotNull();
        assertThat(decision.get("actorKind").asString()).isEqualTo("PLATFORM_STAFF");
        assertThat(decision.get("reason").asString()).isEqualTo("Trade licence matches the registry");
        assertThat(decision.get("after").get("status").asString()).isEqualTo("APPROVED");
        assertThat(audit.toString()).contains("file.accessed");
    }

    @Test
    void staffSeeOnlyTheirMarketsAndCannotDecideForTheirOwnOrganization() throws Exception {
        Org org = orgAwaitingVerification("Lambda Nails");
        String kenya = staffToken("RISK_ANALYST", "KE");
        assertThat(call(get("/v1/admin/verification-cases?limit=100"), null, kenya)
                        .body()
                        .toString())
                .doesNotContain(org.caseId().toString());
        assertThat(call(get("/v1/admin/verification-cases/" + org.caseId()), null, kenya)
                        .status())
                .isEqualTo(404);
        assertThat(call(get("/v1/admin/audit-log"), null, kenya).status())
                .as("market-limited staff must name an organization")
                .isEqualTo(400);

        // The owner also works for the platform: someone else must decide.
        grant(org.owner(), "RISK_ANALYST", null);
        Call ownerSignIn = signInWithOtp(org.owner().phone(), "ADMIN_CONSOLE");
        String ownerAsStaff = stepUp(new Person(org.owner().phone(), ownerSignIn.text("accessToken")));
        assertThat(call(
                                post("/v1/admin/verification-cases/" + org.caseId() + "/decision"),
                                Map.of("decision", "APPROVE", "reason", "Looks fine to me"),
                                ownerAsStaff)
                        .status())
                .isEqualTo(403);
    }

    @Test
    void operationsSearchOrganizationsAndPeopleAndFlagsNeedGlobalMarketManagement() throws Exception {
        Org org = orgAwaitingVerification("Mu Wellness Studio");
        String ops = staffToken("OPS_ADMIN", null);

        JsonNode found =
                call(get("/v1/admin/orgs?q=wellness studio"), null, ops).body().get("items");
        assertThat(found.toString()).contains(org.id().toString());
        Call view = call(get("/v1/admin/orgs/" + org.id()), null, ops);
        assertThat(view.body().get("owner").get("phone").asString())
                .isEqualTo(org.owner().phone());
        assertThat(view.body().get("memberCount").asInt()).isEqualTo(1);
        assertThat(view.body().get("verificationCases").size()).isEqualTo(1);
        assertThat(view.body().get("recentAudit").toString()).contains("organization.created");

        String localNumber = "0" + org.owner().phone().substring(4);
        JsonNode people =
                call(get("/v1/admin/users?q=" + localNumber), null, ops).body().get("items");
        assertThat(people.toString()).contains(org.owner().phone());

        String code =
                "TEST_FLAG_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        system.insertInto(FEATURE_FLAGS)
                .set(FEATURE_FLAGS.CODE, code)
                .set(FEATURE_FLAGS.DESCRIPTION, "Test flag")
                .execute();
        assertThat(call(get("/v1/admin/feature-flags"), null, ops).body().toString())
                .contains(code);
        Map<String, Object> enable = Map.of("defaultEnabled", true, "reason", "Pilot launch");
        assertThat(call(put("/v1/admin/feature-flags/" + code), enable, ops).status())
                .isEqualTo(403);
        Person superAdmin = staff("SUPER_ADMIN", null);
        assertThat(call(put("/v1/admin/feature-flags/" + code), enable, superAdmin.token())
                        .text("code"))
                .isEqualTo("STEP_UP_REQUIRED");
        Call enabled = call(put("/v1/admin/feature-flags/" + code), enable, stepUp(superAdmin));
        assertThat(enabled.status()).isEqualTo(200);
        assertThat(enabled.body().get("defaultEnabled").asBoolean()).isTrue();
    }
}
