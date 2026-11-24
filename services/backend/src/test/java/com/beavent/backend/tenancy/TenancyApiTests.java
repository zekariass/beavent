package com.beavent.backend.tenancy;

import static com.beavent.db.platform.Tables.FILES;
import static com.beavent.db.ref.Tables.ADMINISTRATIVE_AREAS;
import static com.beavent.db.ref.Tables.CATEGORIES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.beavent.backend.ApiTestSupport;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Organizations and access (plan step 0.8), end to end through the API. */
class TenancyApiTests extends ApiTestSupport {

    UUID hairSalon;
    UUID subCity;

    @BeforeEach
    void referenceData() {
        hairSalon = system.select(CATEGORIES.ID)
                .from(CATEGORIES)
                .where(CATEGORIES.KIND.eq("BUSINESS"), CATEGORIES.CODE.eq("HAIR_SALON"))
                .fetchSingle(CATEGORIES.ID);
        subCity = system.select(ADMINISTRATIVE_AREAS.ID)
                .from(ADMINISTRATIVE_AREAS)
                .where(ADMINISTRATIVE_AREAS.COUNTRY_CODE.eq("ET"), ADMINISTRATIVE_AREAS.LEVEL.eq((short) 2))
                .limit(1)
                .fetchSingle(ADMINISTRATIVE_AREAS.ID);
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    Call createOrg(Person owner, String name) throws Exception {
        return call(
                post("/v1/orgs"),
                Map.of(
                        "displayName",
                        name,
                        "kind",
                        "BUSINESS",
                        "primaryCategoryId",
                        hairSalon,
                        "defaultLanguage",
                        "am"),
                owner.token());
    }

    UUID newOrg(Person owner, String name) throws Exception {
        Call created = createOrg(owner, name);
        assertThat(created.status()).as(String.valueOf(created.body())).isEqualTo(201);
        return UUID.fromString(created.text("id"));
    }

    UUID newLegalEntity(Person owner, UUID orgId) throws Exception {
        Call created = call(
                post("/v1/orgs/" + orgId + "/legal-entities"),
                Map.of(
                        "countryCode", "ET",
                        "entityType", "SOLE_PROPRIETOR",
                        "registeredName", "Selam Tesfaye",
                        "tin", "0012345678",
                        "taxRegime", "TOT"),
                owner.token());
        assertThat(created.status()).as(String.valueOf(created.body())).isEqualTo(201);
        return UUID.fromString(created.text("id"));
    }

    Map<String, Object> locationInput(UUID legalEntityId, String name) {
        Map<String, Object> input = new HashMap<>();
        input.put("name", name);
        input.put("legalEntityId", legalEntityId);
        input.put("timezone", "Africa/Addis_Ababa");
        input.put(
                "address",
                Map.of(
                        "countryCode",
                        "ET",
                        "adminAreaId",
                        subCity,
                        "landmark",
                        "Behind Edna Mall, 2nd gate",
                        "geo",
                        Map.of("latitude", 8.9956, "longitude", 38.7891)));
        input.put(
                "hours",
                List.of(
                        Map.of("isoWeekday", 1, "opensAt", "08:30", "closesAt", "12:30"),
                        Map.of("isoWeekday", 1, "opensAt", "13:30", "closesAt", "19:00")));
        return input;
    }

    UUID newLocation(Person owner, UUID orgId, UUID legalEntityId, String name) throws Exception {
        Call created =
                call(post("/v1/orgs/" + orgId + "/locations"), locationInput(legalEntityId, name), owner.token());
        assertThat(created.status()).as(String.valueOf(created.body())).isEqualTo(201);
        return UUID.fromString(created.text("id"));
    }

    UUID roleId(Person owner, UUID orgId, String code) throws Exception {
        for (JsonNode role : call(get("/v1/orgs/" + orgId + "/roles"), null, owner.token())
                .body()
                .get("items")) {
            if (role.get("code").asString().equals(code)) {
                return UUID.fromString(role.get("id").asString());
            }
        }
        throw new AssertionError("no role " + code);
    }

    Call invite(String token, UUID orgId, String phone, UUID roleId, List<UUID> locationIds) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("phone", phone);
        body.put("roleId", roleId);
        if (locationIds != null) {
            body.put("locationIds", locationIds);
        }
        return call(post("/v1/orgs/" + orgId + "/invitations"), body, token);
    }

    String invitationToken(String phone) {
        Matcher matcher = Pattern.compile("token=([A-Za-z0-9_-]+)").matcher(sms.lastMessageTo(phone));
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    /** Invites a new person with the given role and has them accept. */
    Person newMember(Person owner, String ownerStepUp, UUID orgId, String roleCode, List<UUID> locations)
            throws Exception {
        String phone = newPhone();
        Call invited = invite(ownerStepUp, orgId, phone, roleId(owner, orgId, roleCode), locations);
        assertThat(invited.status()).as(String.valueOf(invited.body())).isEqualTo(201);
        String token = invitationToken(phone);
        Call signedIn = signInWithOtp(phone);
        Person member = new Person(phone, signedIn.text("accessToken"));
        Call accepted = call(post("/v1/invitations/accept"), Map.of("token", token), member.token());
        assertThat(accepted.status()).as(String.valueOf(accepted.body())).isEqualTo(200);
        return member;
    }

    UUID kycFile(UUID orgId) {
        UUID id = UUID.randomUUID();
        system.insertInto(FILES)
                .set(FILES.ID, id)
                .set(FILES.ORG_ID, orgId)
                .set(FILES.PURPOSE, "KYC")
                .set(FILES.VISIBILITY, "SENSITIVE")
                .set(FILES.BUCKET, "beavent-private")
                .set(FILES.STORAGE_KEY, "test/" + id)
                .set(FILES.CONTENT_TYPE, "application/pdf")
                .set(FILES.SIZE_BYTES, 1000L)
                .set(FILES.UPLOADED_AT, java.time.OffsetDateTime.now())
                .set(FILES.SCAN_STATUS, "CLEAN")
                .execute();
        return id;
    }

    static List<String> ids(JsonNode items) {
        List<String> ids = new ArrayList<>();
        items.forEach(item -> ids.add(item.get("id").asString()));
        return ids;
    }

    // --- tests -----------------------------------------------------------------------------------------------

    @Test
    void ownerOnboardsAnOrganizationWithLegalEntityLocationAndCapabilities() throws Exception {
        Person owner = newPerson();
        Call created = createOrg(owner, "Abeba Beauty Salon");
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.text("slug")).startsWith("abeba-beauty-salon");
        assertThat(created.text("status")).isEqualTo("ONBOARDING");
        assertThat(created.text("marketCode")).isEqualTo("ET");
        assertThat(created.body().get("capabilities").toString()).isEqualTo("[\"APPOINTMENTS\"]");
        assertThat(created.etag())
                .isEqualTo("\"" + created.body().get("version").asLong() + "\"");
        UUID orgId = UUID.fromString(created.text("id"));

        Call me = call(get("/v1/me"), null, owner.token());
        JsonNode membership = me.body().get("memberships").get(0);
        assertThat(membership.get("orgId").asString()).isEqualTo(orgId.toString());
        assertThat(membership.get("isOwner").asBoolean()).isTrue();

        // Same name again: a free slug is chosen; a reserved slug is refused.
        Call second = createOrg(newPerson(), "Abeba Beauty Salon");
        assertThat(second.text("slug")).isNotEqualTo(created.text("slug"));
        Map<String, Object> reserved = new HashMap<>(Map.of(
                "displayName",
                "Admin",
                "slug",
                "admin",
                "kind",
                "BUSINESS",
                "primaryCategoryId",
                hairSalon,
                "defaultLanguage",
                "am"));
        assertThat(call(post("/v1/orgs"), reserved, owner.token()).status()).isEqualTo(409);
        reserved.putAll(Map.of("slug", "kenya-salon", "marketCode", "KE"));
        assertThat(call(post("/v1/orgs"), reserved, owner.token()).status())
                .as("markets that are not open")
                .isEqualTo(422);

        // Profile update needs the current version.
        Call stale = call(
                patch("/v1/orgs/" + orgId).header("If-Match", "\"1\""), Map.of("displayName", "Abeba"), owner.token());
        assertThat(stale.status()).isEqualTo(412);
        Call updated = call(
                patch("/v1/orgs/" + orgId).header("If-Match", created.etag()),
                Map.of("displayName", "Abeba Beauty", "descriptionI18n", Map.of("en", "Hair and nails", "am", "ፀጉር")),
                owner.token());
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.text("displayName")).isEqualTo("Abeba Beauty");
        assertThat(updated.etag()).isNotEqualTo(created.etag());

        Call capabilities = call(
                put("/v1/orgs/" + orgId + "/capabilities"),
                Map.of("capabilities", List.of("APPOINTMENTS", "RETAIL", "GIFT_CARDS")),
                owner.token());
        assertThat(capabilities.body().get("capabilities").toString())
                .isEqualTo("[\"APPOINTMENTS\",\"GIFT_CARDS\",\"RETAIL\"]");

        // VAT regime requires a VAT number.
        Call noVat = call(
                post("/v1/orgs/" + orgId + "/legal-entities"),
                Map.of("countryCode", "ET", "entityType", "PLC", "registeredName", "Abeba PLC", "taxRegime", "VAT"),
                owner.token());
        assertThat(noVat.status()).isEqualTo(422);
        UUID legalEntityId = newLegalEntity(owner, orgId);
        JsonNode entities = call(get("/v1/orgs/" + orgId + "/legal-entities"), null, owner.token())
                .body();
        assertThat(entities.get("items").get(0).get("isDefault").asBoolean()).isTrue();
        assertThat(entities.get("items").get(0).get("functionalCurrency").asString())
                .isEqualTo("ETB");

        // Ethiopian addresses need a sub-city/woreda and a landmark.
        Map<String, Object> noLandmark = locationInput(legalEntityId, "Bole branch");
        noLandmark.put("address", Map.of("countryCode", "ET", "adminAreaId", subCity));
        assertThat(call(post("/v1/orgs/" + orgId + "/locations"), noLandmark, owner.token())
                        .status())
                .isEqualTo(400);
        Map<String, Object> overlapping = locationInput(legalEntityId, "Bole branch");
        overlapping.put(
                "hours",
                List.of(
                        Map.of("isoWeekday", 2, "opensAt", "08:00", "closesAt", "12:00"),
                        Map.of("isoWeekday", 2, "opensAt", "11:00", "closesAt", "15:00")));
        assertThat(call(post("/v1/orgs/" + orgId + "/locations"), overlapping, owner.token())
                        .status())
                .isEqualTo(400);

        Call location = call(
                post("/v1/orgs/" + orgId + "/locations"), locationInput(legalEntityId, "Bole branch"), owner.token());
        assertThat(location.status()).isEqualTo(201);
        assertThat(location.text("slug")).isEqualTo("bole-branch");
        assertThat(location.text("currency")).isEqualTo("ETB");
        assertThat(location.body().get("address").get("geo").get("latitude").asDouble())
                .isEqualTo(8.9956);
        assertThat(location.body().get("hours").size()).isEqualTo(2);
        String locationPath = "/v1/orgs/" + orgId + "/locations/" + location.text("id");
        Map<String, Object> renamed = locationInput(legalEntityId, "Bole Medhanialem branch");
        renamed.put("hours", List.of(Map.of("isoWeekday", 6, "opensAt", "09:00", "closesAt", "17:00")));
        Call changed = call(put(locationPath).header("If-Match", location.etag()), renamed, owner.token());
        assertThat(changed.status()).isEqualTo(200);
        assertThat(changed.text("name")).isEqualTo("Bole Medhanialem branch");
        assertThat(changed.body().get("hours").size()).isEqualTo(1);
        assertThat(call(put(locationPath).header("If-Match", location.etag()), renamed, owner.token())
                        .status())
                .as("stale version")
                .isEqualTo(412);
        assertThat(call(get("/v1/orgs/" + orgId + "/locations"), null, owner.token())
                        .body()
                        .get("items")
                        .size())
                .isEqualTo(1);
    }

    @Test
    void anOrganizationsTokenCanNeverReadOrWriteAnotherOrganizationsData() throws Exception {
        Person alice = newPerson();
        UUID orgA = newOrg(alice, "Alpha Spa");
        UUID entityA = newLegalEntity(alice, orgA);
        UUID locationA = newLocation(alice, orgA, entityA, "Alpha main");

        Person bob = newPerson();
        UUID orgB = newOrg(bob, "Beta Barbers");
        UUID entityB = newLegalEntity(bob, orgB);
        UUID locationB = newLocation(bob, orgB, entityB, "Beta main");
        UUID fileB = kycFile(orgB);
        String a = alice.token();

        // Another organization's paths: indistinguishable from organizations that do not exist.
        for (String path : List.of(
                "/v1/orgs/" + orgB,
                "/v1/orgs/" + orgB + "/locations",
                "/v1/orgs/" + orgB + "/locations/" + locationB,
                "/v1/orgs/" + orgB + "/legal-entities",
                "/v1/orgs/" + orgB + "/members",
                "/v1/orgs/" + orgB + "/verification-cases")) {
            assertThat(call(get(path), null, a).status()).as(path).isEqualTo(404);
            assertThat(call(get("/v1/orgs/" + UUID.randomUUID()), null, a).status())
                    .isEqualTo(404);
        }
        assertThat(call(patch("/v1/orgs/" + orgB).header("If-Match", "*"), Map.of("displayName", "Hacked"), a)
                        .status())
                .isEqualTo(404);
        assertThat(call(post("/v1/orgs/" + orgB + "/locations"), locationInput(entityB, "Sneaky"), a)
                        .status())
                .isEqualTo(404);

        // B's ids used inside A's workspace: not found, or refused as unknown references.
        assertThat(call(get("/v1/orgs/" + orgA + "/locations/" + locationB), null, a)
                        .status())
                .isEqualTo(404);
        assertThat(call(
                                put("/v1/orgs/" + orgA + "/locations/" + locationB)
                                        .header("If-Match", "*"),
                                locationInput(entityA, "Taken over"),
                                a)
                        .status())
                .isEqualTo(404);
        assertThat(call(
                                put("/v1/orgs/" + orgA + "/legal-entities/" + entityB),
                                Map.of(
                                        "countryCode", "ET",
                                        "entityType", "PLC",
                                        "registeredName", "Hacked",
                                        "taxRegime", "TOT"),
                                a)
                        .status())
                .isEqualTo(404);
        assertThat(call(post("/v1/orgs/" + orgA + "/locations"), locationInput(entityB, "Borrowed entity"), a)
                        .status())
                .isEqualTo(422);
        assertThat(invite(a, orgA, newPhone(), roleId(alice, orgA, "RECEPTIONIST"), List.of(locationB))
                        .status())
                .isEqualTo(422);
        assertThat(call(
                                post("/v1/orgs/" + orgA + "/verification-cases"),
                                Map.of(
                                        "subject",
                                        "ORGANIZATION_KYB",
                                        "legalEntityId",
                                        entityA,
                                        "documents",
                                        List.of(Map.of("docType", "TRADE_LICENSE", "fileId", fileB))),
                                a)
                        .status())
                .isEqualTo(422);
        assertThat(call(get("/v1/orgs/" + orgA + "/locations"), null, a)
                        .body()
                        .get("items")
                        .toString())
                .contains(locationA.toString())
                .doesNotContain(locationB.toString());

        // Someone in both organizations: each workspace lists only its own memberships.
        Person both = newMember(bob, stepUp(bob), orgB, "RECEPTIONIST", null);
        String stepUpAlice = stepUp(alice);
        String phone = both.phone();
        assertThat(invite(stepUpAlice, orgA, phone, roleId(alice, orgA, "RECEPTIONIST"), null)
                        .status())
                .isEqualTo(201);
        Call accepted = call(post("/v1/invitations/accept"), Map.of("token", invitationToken(phone)), both.token());
        assertThat(accepted.status()).isEqualTo(200);
        JsonNode membersA =
                call(get("/v1/orgs/" + orgA + "/members"), null, a).body().get("items");
        assertThat(membersA.size()).isEqualTo(2);
        String memberInB = call(get("/v1/orgs/" + orgB + "/members"), null, bob.token())
                .body()
                .get("items")
                .toString();
        for (String memberId : ids(call(get("/v1/orgs/" + orgB + "/members"), null, bob.token())
                .body()
                .get("items"))) {
            assertThat(ids(membersA)).doesNotContain(memberId);
            assertThat(call(
                                    patch("/v1/orgs/" + orgA + "/members/" + memberId),
                                    Map.of("allLocations", true),
                                    stepUpAlice)
                            .status())
                    .isEqualTo(404);
        }
        assertThat(memberInB).doesNotContain(alice.phone());
        assertThat(call(get("/v1/me"), null, both.token())
                        .body()
                        .get("memberships")
                        .size())
                .isEqualTo(2);
    }

    @Test
    void invitationsReachOnlyTheInvitedPersonAndRolesLimitWhatMembersCanDo() throws Exception {
        Person owner = newPerson();
        UUID orgId = newOrg(owner, "Gamma Studio");
        UUID entity = newLegalEntity(owner, orgId);
        UUID branch1 = newLocation(owner, orgId, entity, "Piassa");
        UUID branch2 = newLocation(owner, orgId, entity, "Kazanchis");
        String phone = newPhone();
        UUID receptionist = roleId(owner, orgId, "RECEPTIONIST");

        Call invited = invite(owner.token(), orgId, phone, receptionist, List.of(branch1));
        assertThat(invited.status()).isEqualTo(201);
        assertThat(invite(owner.token(), orgId, phone, receptionist, null).status())
                .as("one pending invitation per person")
                .isEqualTo(409);
        String token = invitationToken(phone);

        // Someone else who got the link cannot use it.
        Person stranger = newPerson();
        assertThat(call(post("/v1/invitations/accept"), Map.of("token", token), stranger.token())
                        .status())
                .isEqualTo(403);
        Person invitee = new Person(phone, signInWithOtp(phone).text("accessToken"));
        Call accepted = call(post("/v1/invitations/accept"), Map.of("token", token), invitee.token());
        assertThat(accepted.status()).isEqualTo(200);
        assertThat(accepted.text("roleCode")).isEqualTo("RECEPTIONIST");
        assertThat(accepted.body().get("allLocations").asBoolean()).isFalse();
        assertThat(call(post("/v1/invitations/accept"), Map.of("token", token), invitee.token())
                        .status())
                .as("single use")
                .isEqualTo(404);

        // Receptionist: reads the organization and their own branch only; cannot change settings or the team.
        assertThat(call(get("/v1/orgs/" + orgId), null, invitee.token()).status())
                .isEqualTo(200);
        JsonNode visible = call(get("/v1/orgs/" + orgId + "/locations"), null, invitee.token())
                .body()
                .get("items");
        assertThat(ids(visible)).containsExactly(branch1.toString());
        assertThat(call(get("/v1/orgs/" + orgId + "/locations/" + branch2), null, invitee.token())
                        .status())
                .isEqualTo(404);
        Call forbidden = call(
                patch("/v1/orgs/" + orgId).header("If-Match", "*"), Map.of("displayName", "Mine now"), invitee.token());
        assertThat(forbidden.status()).isEqualTo(403);
        assertThat(forbidden.text("code")).isEqualTo("FORBIDDEN");
        assertThat(call(get("/v1/orgs/" + orgId + "/members"), null, invitee.token())
                        .status())
                .isEqualTo(403);

        // Inviting into a role with sensitive permissions needs step-up.
        Call needsStepUp = invite(owner.token(), orgId, newPhone(), roleId(owner, orgId, "ADMIN"), null);
        assertThat(needsStepUp.status()).isEqualTo(403);
        assertThat(needsStepUp.text("code")).isEqualTo("STEP_UP_REQUIRED");
        String ownerStepUp = stepUp(owner);
        Call adminInvite = invite(ownerStepUp, orgId, newPhone(), roleId(owner, orgId, "ADMIN"), null);
        assertThat(adminInvite.status()).isEqualTo(201);
        assertThat(call(delete("/v1/orgs/" + orgId + "/invitations/" + adminInvite.text("id")), null, owner.token())
                        .status())
                .isEqualTo(204);
        JsonNode invitations = call(get("/v1/orgs/" + orgId + "/invitations"), null, owner.token())
                .body();
        assertThat(invitations.toString()).contains("REVOKED").contains("ACCEPTED");

        // Changing someone's role or removing them needs step-up; afterwards their token no longer opens the org.
        String memberId = accepted.text("id");
        UUID cashier = roleId(owner, orgId, "CASHIER");
        assertThat(call(patch("/v1/orgs/" + orgId + "/members/" + memberId), Map.of("roleId", cashier), owner.token())
                        .text("code"))
                .isEqualTo("STEP_UP_REQUIRED");
        Call changed = call(
                patch("/v1/orgs/" + orgId + "/members/" + memberId),
                Map.of("roleId", cashier, "allLocations", true),
                ownerStepUp);
        assertThat(changed.status()).isEqualTo(200);
        assertThat(changed.text("roleCode")).isEqualTo("CASHIER");
        assertThat(changed.body().get("locationIds").size()).isZero();
        assertThat(call(delete("/v1/orgs/" + orgId + "/members/" + memberId), null, ownerStepUp)
                        .status())
                .isEqualTo(204);
        assertThat(call(get("/v1/orgs/" + orgId), null, invitee.token()).status())
                .isEqualTo(404);
        assertThat(call(get("/v1/me"), null, invitee.token())
                        .body()
                        .get("memberships")
                        .size())
                .isZero();
    }

    @Test
    void customRolesCannotEscalatePrivilegesAndTheOwnerIsProtected() throws Exception {
        Person owner = newPerson();
        UUID orgId = newOrg(owner, "Delta Events");
        String ownerStepUp = stepUp(owner);

        Call teamLead = call(
                post("/v1/orgs/" + orgId + "/roles"),
                Map.of("name", "Team lead", "permissions", List.of("org.members.manage", "calendar.view_all")),
                owner.token());
        assertThat(teamLead.text("code")).as("sensitive grant without step-up").isEqualTo("STEP_UP_REQUIRED");
        teamLead = call(
                post("/v1/orgs/" + orgId + "/roles"),
                Map.of("name", "Team lead", "permissions", List.of("org.members.manage", "calendar.view_all")),
                ownerStepUp);
        assertThat(teamLead.status()).isEqualTo(201);
        assertThat(teamLead.text("code")).isEqualTo("CUSTOM_TEAM_LEAD");
        assertThat(call(
                                post("/v1/orgs/" + orgId + "/roles"),
                                Map.of("name", "team LEAD", "permissions", List.of()),
                                owner.token())
                        .status())
                .as("duplicate name")
                .isEqualTo(409);
        assertThat(call(
                                post("/v1/orgs/" + orgId + "/roles"),
                                Map.of("name", "Odd", "permissions", List.of("no.such")),
                                owner.token())
                        .status())
                .isEqualTo(422);
        UUID systemRole = roleId(owner, orgId, "MANAGER");
        assertThat(call(patch("/v1/orgs/" + orgId + "/roles/" + systemRole), Map.of("name", "Boss"), owner.token())
                        .status())
                .isEqualTo(409);

        Person lead = newMember(owner, ownerStepUp, orgId, "CUSTOM_TEAM_LEAD", null);
        String leadStepUp = stepUp(lead);
        // The lead can manage members but cannot grant what they do not hold.
        assertThat(call(
                                post("/v1/orgs/" + orgId + "/roles"),
                                Map.of("name", "Refunder", "permissions", List.of("refunds.create")),
                                leadStepUp)
                        .status())
                .isEqualTo(403);
        assertThat(invite(leadStepUp, orgId, newPhone(), roleId(owner, orgId, "MANAGER"), null)
                        .status())
                .isEqualTo(403);
        Call viewer = invite(leadStepUp, orgId, newPhone(), roleId(owner, orgId, "VIEWER"), null);
        assertThat(viewer.status()).isEqualTo(403).as("VIEWER holds reports.view, which the lead lacks");

        String ownerMemberId = null;
        for (JsonNode member : call(get("/v1/orgs/" + orgId + "/members"), null, lead.token())
                .body()
                .get("items")) {
            if (member.get("isOwner").asBoolean()) {
                ownerMemberId = member.get("id").asString();
            }
        }
        Call demoteOwner = call(
                patch("/v1/orgs/" + orgId + "/members/" + ownerMemberId), Map.of("status", "SUSPENDED"), leadStepUp);
        assertThat(demoteOwner.status()).isEqualTo(422);
        assertThat(call(delete("/v1/orgs/" + orgId + "/members/" + ownerMemberId), null, leadStepUp)
                        .status())
                .isEqualTo(422);

        UUID leadRole = UUID.fromString(teamLead.text("id"));
        assertThat(call(delete("/v1/orgs/" + orgId + "/roles/" + leadRole), null, owner.token())
                        .status())
                .as("role in use")
                .isEqualTo(409);
        Call trimmed = call(
                patch("/v1/orgs/" + orgId + "/roles/" + leadRole),
                Map.of("permissions", List.of("org.members.manage")),
                owner.token());
        assertThat(trimmed.body().get("permissions").toString()).isEqualTo("[\"org.members.manage\"]");
    }

    @Test
    void businessVerificationIsSubmittedOnceAndOnlyTheOwnerSubmitsIdentity() throws Exception {
        Person owner = newPerson();
        UUID orgId = newOrg(owner, "Epsilon Wellness");
        UUID entity = newLegalEntity(owner, orgId);
        Map<String, Object> kyb = Map.of(
                "subject",
                "ORGANIZATION_KYB",
                "legalEntityId",
                entity,
                "documents",
                List.of(
                        Map.of("docType", "TRADE_LICENSE", "fileId", kycFile(orgId)),
                        Map.of("docType", "TIN_CERTIFICATE", "fileId", kycFile(orgId), "expiresOn", "2030-01-01")));
        Call submitted = call(post("/v1/orgs/" + orgId + "/verification-cases"), kyb, owner.token());
        assertThat(submitted.status()).isEqualTo(201);
        assertThat(submitted.text("status")).isEqualTo("OPEN");
        assertThat(submitted.body().get("documents").size()).isEqualTo(2);
        assertThat(call(post("/v1/orgs/" + orgId + "/verification-cases"), kyb, owner.token())
                        .status())
                .isEqualTo(409);
        assertThat(call(get("/v1/orgs/" + orgId), null, owner.token()).text("verificationStatus"))
                .isEqualTo("PENDING");
        assertThat(call(get("/v1/orgs/" + orgId + "/verification-cases/" + submitted.text("id")), null, owner.token())
                        .status())
                .isEqualTo(200);

        Person admin = newMember(owner, stepUp(owner), orgId, "ADMIN", null);
        Call ownerKyc = call(
                post("/v1/orgs/" + orgId + "/verification-cases"),
                Map.of(
                        "subject",
                        "OWNER_KYC",
                        "documents",
                        List.of(Map.of("docType", "FAYDA_ID", "fileId", kycFile(orgId)))),
                admin.token());
        assertThat(ownerKyc.status()).isEqualTo(403);
    }

    @Test
    void referenceDataIsPublicExceptThePermissionCatalogue() throws Exception {
        JsonNode markets = call(get("/v1/ref/markets"), null, null).body().get("items");
        JsonNode ethiopia = null;
        for (JsonNode market : markets) {
            if (market.get("code").asString().equals("ET")) {
                ethiopia = market;
            }
        }
        assertThat(ethiopia).isNotNull();
        assertThat(ethiopia.get("displayCalendar").asString()).isEqualTo("ETHIOPIAN");
        assertThat(ethiopia.get("features").get("PROVIDER_SPLIT").asString()).isEqualTo("ENABLED");
        assertThat(ethiopia.get("currencies").get(0).get("code").asString()).isEqualTo("ETB");

        assertThat(call(get("/v1/ref/languages"), null, null)
                        .body()
                        .get("items")
                        .toString())
                .contains("አማርኛ");
        JsonNode categories =
                call(get("/v1/ref/categories?kind=BUSINESS"), null, null).body().get("items");
        assertThat(categories.toString()).contains("HAIR_SALON").doesNotContain("\"kind\":\"SERVICE\"");
        JsonNode regions = call(get("/v1/ref/admin-areas?countryCode=ET"), null, null)
                .body()
                .get("items");
        assertThat(regions.size()).isGreaterThanOrEqualTo(14);
        assertThat(callInvalid(get("/v1/ref/admin-areas"), null, null).status()).isEqualTo(400);

        assertThat(call(get("/v1/ref/permissions"), null, null).status()).isEqualTo(401);
        JsonNode permissions = call(
                        get("/v1/ref/permissions"), null, newPerson().token())
                .body()
                .get("items");
        assertThat(permissions.toString()).contains("org.members.manage").doesNotContain("admin.kyb.decide");
    }
}
